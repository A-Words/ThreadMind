package app.threadmind

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.threadmind.provider.ProviderPreflightResult
import app.threadmind.provider.ProviderTarget
import app.threadmind.ui.workspace.displayDate

@Composable
internal fun ProviderReviewDialog(
    review: ProviderPreflightResult,
    onApprove: () -> Unit,
    onConvert: (app.threadmind.provider.ContactCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (review) {
        is ProviderPreflightResult.MeetingConflicts -> "发现疑似重复会议"
        is ProviderPreflightResult.ContactCandidates -> if (review.createContact) "发现可能重复的联系人" else "请选择唯一联系人"
        is ProviderPreflightResult.ContactOverwrites -> "确认联系人字段差异"
        is ProviderPreflightResult.Blocked -> "设备数据检查未通过"
        is ProviderPreflightResult.Clear -> "设备数据检查完成"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (review) {
                    is ProviderPreflightResult.MeetingConflicts -> review.items.forEach {
                        Text("${it.title} · ${displayDate(java.time.Instant.ofEpochMilli(it.startsAtEpochMillis).toString())} — ${displayDate(java.time.Instant.ofEpochMilli(it.endsAtEpochMillis).toString())}")
                    }
                    is ProviderPreflightResult.ContactCandidates -> review.items.forEach { candidate ->
                        Text("${candidate.displayName.ifBlank { "未命名联系人" }} · ${candidate.accountName ?: "本地账户"} · ${candidate.matchedBy}")
                        candidate.proposedChanges.forEach { change ->
                            Text("${contactFieldLabel(change.field)}：${change.oldValue ?: "未设置"} → ${change.newValue}")
                        }
                        TextButton(
                            onClick = { onConvert(candidate) },
                            enabled = candidate.proposedChanges.isNotEmpty(),
                        ) { Text("改为更新此联系人") }
                    }
                    is ProviderPreflightResult.ContactOverwrites -> review.changes.forEach { change ->
                        Text("${contactFieldLabel(change.field)}：${change.oldValue ?: "未设置"} → ${change.newValue}")
                    }
                    is ProviderPreflightResult.Blocked -> Text(review.message)
                    is ProviderPreflightResult.Clear -> Text("没有发现需要额外确认的问题。")
                }
            }
        },
        confirmButton = {
            when (review) {
                is ProviderPreflightResult.MeetingConflicts -> Button(onClick = onApprove) { Text("仍然保留本次会议") }
                is ProviderPreflightResult.ContactCandidates -> if (review.createContact) {
                    Button(onClick = onApprove) { Text("仍然创建新联系人") }
                }
                is ProviderPreflightResult.ContactOverwrites -> Button(onClick = onApprove) { Text("确认覆盖这些字段") }
                is ProviderPreflightResult.Blocked,
                is ProviderPreflightResult.Clear -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

private fun contactFieldLabel(field: String) = when (field) {
    "displayName" -> "姓名"
    "email" -> "邮箱"
    "phone" -> "电话"
    "company" -> "公司"
    "jobTitle" -> "职位"
    "address" -> "地址"
    "note", "notes" -> "备注"
    else -> "联系人信息"
}

@Composable
internal fun ProviderTargetDialog(
    targets: List<ProviderTarget>,
    onSelect: (ProviderTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标账户") },
        text = {
            LazyColumn {
                items(targets) { target ->
                    TextButton(onClick = { onSelect(target) }, modifier = Modifier.fillMaxWidth()) {
                        Text(target.label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}
