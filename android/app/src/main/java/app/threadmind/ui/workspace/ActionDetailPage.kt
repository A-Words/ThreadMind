package app.threadmind.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.threadmind.domain.*
import java.time.*

@Composable
fun ActionDetailPage(
    card: ActionCard, pending: Boolean, readOnly: Boolean, receiptPending: Boolean, reviewed: Boolean, reviewPending: Boolean,
    onSave: (Map<String, String>, Set<String>) -> Unit, onSelectTarget: () -> Unit, onPreflight: () -> Unit,
    onConfirm: () -> Unit, onExecute: () -> Unit, onCancel: () -> Unit, onRetryReceipt: () -> Unit, onDirty: (Boolean) -> Unit,
) {
    var fields by rememberSaveable(card.id, card.version) { mutableStateOf(HashMap(card.fields)) }
    var resolved by rememberSaveable(card.id, card.version) { mutableStateOf(arrayListOf<String>()) }
    var evidence by rememberSaveable(card.id) { mutableStateOf(false) }
    var cancelDialog by remember { mutableStateOf(false) }
    val changed = fields != card.fields || resolved.isNotEmpty()
    val editable = !readOnly && !receiptPending && card.status !in setOf(ActionStatus.EXECUTING, ActionStatus.SUCCEEDED, ActionStatus.CANCELLED)
    val hasTarget = !card.targetAccountId.isNullOrBlank()
    LaunchedEffect(changed) { onDirty(changed) }
    WorkspaceList {
        item { PageHeader(card.title(), "${card.type.label()} · 第 ${card.version} 版") }
        item { StatusPill(card.status.label(), card.status == ActionStatus.BLOCKED || card.status == ActionStatus.FAILED) }
        if (readOnly) item { InfoPanel("这是从云端恢复的已确认或执行中记录。本机没有对应执行凭据，请回到原设备处理；这里仅供查看。") }
        if (receiptPending) item { InfoPanel("系统写入结果已保存在本机，云端回执尚未同步。请勿再次执行。", "同步回执", onRetryReceipt) }
        item { SectionHeading("写入到哪里") }
        item {
            InfoPanel(fields["targetCalendarName"] ?: fields["accountName"]?.takeIf(String::isNotBlank)
                ?: if (hasTarget) "已选择设备账户" else "先选择目标日历或联系人账户，再完善内容。")
            if (editable) TextButton(onClick = onSelectTarget, enabled = !pending && !reviewPending && !changed) { Text("选择设备账户") }
        }
        item { SectionHeading("核对行动内容") }
        actionFieldSpecs(card.type).filterNot { it.providerManaged || it.key in setOf("targetCalendarId", "targetContactAccountId") }.forEach { spec ->
            item(key = spec.key) {
                val value = fields[spec.key].orEmpty()
                val confidence = card.fieldConfidence[spec.key]
                val enabled = editable && !pending && hasTarget
                if (spec.key in setOf("startsAt", "endsAt")) {
                    DateTimeField(if (spec.key == "startsAt") "开始时间" else "结束时间", value,
                        fields["timezone"] ?: ZoneId.systemDefault().id, enabled) { fields = HashMap(fields + (spec.key to it)) }
                } else {
                    OutlinedTextField(value = value, onValueChange = { fields = HashMap(fields + (spec.key to it)) },
                        label = { Text(spec.label + if (spec.required) "（必填）" else "") },
                        enabled = enabled, modifier = Modifier.fillMaxWidth(),
                        supportingText = { when {
                            spec.required && value.isBlank() -> Text("请补充${spec.label}")
                            confidence != null && confidence < .8 -> Text("识别置信度 ${(confidence * 100).toInt()}%，请重点核对")
                        } }, isError = spec.required && value.isBlank())
                }
            }
        }
        if (card.type == ActionType.UPDATE_CONTACT) item { InfoPanel("联系人及字段差异将在设备检查中逐项展示，需明确确认后才会覆盖。") }
        if (card.type == ActionType.CREATE_MEETING) item { Text("时间按 ${fields["timezone"] ?: ZoneId.systemDefault().id} 解释；请确认与对话中的时区一致。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        card.validationIssues.forEachIndexed { index, issue -> item(key = "issue:$index") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = issue in resolved, enabled = editable && !pending, onCheckedChange = { checked ->
                    resolved = ArrayList(if (checked) resolved + issue else resolved - issue)
                })
                Text(issueLabel(issue, card.type), Modifier.weight(1f))
            }
        } }
        card.blockers.filterNot { it.startsWith("validation:") }.forEachIndexed { index, issue -> item(key = "blocker:$index") { InfoPanel(issueLabel(issue, card.type)) } }
        item { OutlinedButton(onClick = { evidence = !evidence }, modifier = Modifier.fillMaxWidth()) { Text(if (evidence) "收起来源依据" else "查看来源依据（${card.evidence.size}）") } }
        if (evidence) card.evidence.forEachIndexed { index, source -> item(key = "evidence:$index") { InfoPanel("${source.excerpt}\n置信度 ${(source.confidence * 100).toInt()}%") } }
        if (pending || reviewPending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (editable) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val label = when { changed -> "保存修改"; !reviewed -> "检查设备数据"; card.status == ActionStatus.CONFIRMED -> "授权并写入系统"; else -> "确认当前版本" }
                Button(onClick = {
                    when { changed -> onSave(fields, resolved.toSet()); !reviewed -> onPreflight(); card.status == ActionStatus.CONFIRMED -> onExecute(); else -> onConfirm() }
                }, enabled = !pending && !reviewPending && hasTarget && (changed || !reviewed || card.status in setOf(ActionStatus.READY, ActionStatus.FAILED, ActionStatus.CONFIRMED)), modifier = Modifier.fillMaxWidth()) { Text(label) }
                Text(when { changed -> "保存后需重新检查设备数据并确认。"; !reviewed -> "检查重复项、日历冲突和目标记录，不会写入系统。"; card.status == ActionStatus.CONFIRMED -> "只执行你已确认的当前版本。"; else -> "设备检查已完成，确认后才可授权写入。" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (reviewed && !changed) TextButton(onClick = onPreflight, enabled = !pending && !reviewPending) { Text("重新检查设备数据") }
                TextButton(onClick = { cancelDialog = true }, enabled = !pending) { Text("取消这张行动卡") }
            }
        }
    }
    if (cancelDialog) AlertDialog(onDismissRequest = { cancelDialog = false }, title = { Text("取消这张行动卡？") }, text = { Text("取消后不会写入系统，分析记录仍保留。") },
        confirmButton = { TextButton(onClick = { cancelDialog = false; onCancel() }) { Text("确认取消") } },
        dismissButton = { TextButton(onClick = { cancelDialog = false }) { Text("返回") } })
}

private fun issueLabel(value: String, type: ActionType): String {
    val labels = actionFieldSpecs(type).associate { it.key to it.label }
    val field = value.substringAfter(':', "")
    return when {
        value.startsWith("missing:") -> "请补充${labels[field] ?: "必填信息"}"
        value.any { it.code > 127 } -> value
        field in labels -> "请核对${labels[field]}，确认其与对话一致"
        else -> "这项信息仍有不确定性，请核对来源和目标记录后确认"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeField(label: String, value: String, timezone: String, enabled: Boolean, onChange: (String) -> Unit) {
    var stage by remember { mutableIntStateOf(0) }
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
    val existing = runCatching { Instant.parse(value).atZone(zone) }.getOrDefault(ZonedDateTime.now(zone))
    var date by remember { mutableStateOf(existing.toLocalDate()) }
    OutlinedButton(onClick = { stage = 1 }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("$label：${if (value.isBlank()) "请选择" else runCatching { existing.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")) }.getOrDefault("请重新选择")}")
    }
    if (stage == 1) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = existing.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { stage = 0 }, confirmButton = { TextButton(onClick = {
            picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); stage = 2 }
        }) { Text("下一步") } }, dismissButton = { TextButton(onClick = { stage = 0 }) { Text("取消") } }) { DatePicker(picker) }
    }
    if (stage == 2) {
        val picker = rememberTimePickerState(initialHour = existing.hour, initialMinute = existing.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { stage = 0 }, title = { Text("选择时间 · $timezone") },
            text = { TimeInput(picker) }, confirmButton = { TextButton(onClick = {
                onChange(date.atTime(picker.hour, picker.minute).atZone(zone).toInstant().toString()); stage = 0
            }) { Text("确定") } }, dismissButton = { TextButton(onClick = { stage = 0 }) { Text("取消") } })
    }
}
