package app.threadmind.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.threadmind.MainUiState
import app.threadmind.MainViewModel
import app.threadmind.MemoryTimeFilter
import app.threadmind.network.*

private val memoryTypes = linkedMapOf("" to "全部类型", "event" to "事件", "preference" to "偏好", "relationship" to "关系", "commitment" to "承诺", "profile" to "资料", "other" to "其他")

@Composable
fun MemoriesPage(state: MainUiState, viewModel: MainViewModel, onOpen: (String) -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var timeMenu by remember { mutableStateOf(false) }
    WorkspaceList {
        item { PageHeader("由你掌握的记忆", "看得见来源，也可以随时修订或删除。") }
        item { OutlinedTextField(state.memorySearch, viewModel::setMemorySearch, label = { Text("搜索记忆与来源") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    FilterChip(onClick = { typeMenu = true }, selected = state.memoryType != null, label = { Text(memoryTypes[state.memoryType.orEmpty()] ?: "类型") })
                    DropdownMenu(typeMenu, { typeMenu = false }) { memoryTypes.forEach { (type, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { viewModel.setMemoryType(type.ifBlank { null }); typeMenu = false; viewModel.refreshMemories() })
                    } }
                }
                Box {
                    FilterChip(onClick = { timeMenu = true }, selected = state.memoryTimeFilter != MemoryTimeFilter.ALL, label = { Text(state.memoryTimeFilter.label()) })
                    DropdownMenu(timeMenu, { timeMenu = false }) { MemoryTimeFilter.entries.forEach { time ->
                        DropdownMenuItem(text = { Text(time.label()) }, onClick = { viewModel.setMemoryTimeFilter(time); timeMenu = false; viewModel.refreshMemories() })
                    } }
                }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起筛选" else "高级筛选") }
            }
        }
        if (advanced) item { OutlinedTextField(state.memorySubjectRef, viewModel::setMemorySubjectRef, label = { Text("联系人标识（可选）") }, modifier = Modifier.fillMaxWidth()) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = viewModel::refreshMemories, enabled = !state.isMemoryLoading) { Text("搜索 / 刷新") }
            TextButton(onClick = viewModel::clearMemoryFilters) { Text("清除筛选") }
        } }
        if (state.isMemoryLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.memoryMessage?.let { item { InfoPanel(it) } }
        if (state.memories.isEmpty() && !state.isMemoryLoading) item { EmptyPanel("这里还没有匹配的记忆", "分析与行动会留下有来源的记忆，也可以尝试调整筛选条件。") }
        items(state.memories, key = { it.id }) { memory ->
            SummaryCard(memory.assertion, "${memoryTypes[memory.type] ?: "记忆"} · ${displayDate(memory.updatedAt)} · 第 ${memory.version} 版",
                if (memory.epistemicStatus == "fact") "事实" else "推断") { onOpen(memory.id) }
        }
    }
}

@Composable
fun MemoryDetailPage(memory: MemoryRecordResponse, pending: Boolean, onSave: (String) -> Unit, onDelete: () -> Unit, onSource: (String) -> Unit, onDirty: (Boolean) -> Unit) {
    var editing by rememberSaveable(memory.id, memory.version) { mutableStateOf(false) }
    var text by rememberSaveable(memory.id, memory.version) { mutableStateOf(memory.assertion) }
    val changed = text != memory.assertion
    LaunchedEffect(changed) { onDirty(changed) }
    WorkspaceList {
        item { PageHeader("记忆详情", "这条记录如何形成，由你核对。") }
        item { StatusPill(if (memory.epistemicStatus == "fact") "事实" else "推断") }
        item {
            if (editing) OutlinedTextField(text, { text = it }, label = { Text("修订记忆内容") }, minLines = 4, modifier = Modifier.fillMaxWidth(), enabled = !pending)
            else Text(memory.assertion, style = MaterialTheme.typography.titleLarge)
        }
        item { Text("置信度 ${(memory.confidence * 100).toInt()}% · 第 ${memory.version} 版\n更新于 ${displayDate(memory.updatedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SectionHeading("来源依据") }
        if (memory.sourceEvidence.isEmpty()) item { InfoPanel("这条记忆没有可展示的来源摘录。") }
        items(memory.sourceEvidence.indices.toList()) { index ->
            val evidence = memory.sourceEvidence[index]
            itemEvidence(evidence, onSource)
        }
        item {
            if (!editing) FilledTonalButton(onClick = { editing = true }) { Text("修订这条记忆") }
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(text) }, enabled = changed && text.isNotBlank() && !pending) { Text(if (pending) "保存中…" else "保存修订") }
                TextButton(onClick = { text = memory.assertion; editing = false }, enabled = !pending) { Text("取消修改") }
            }
        }
        item { TextButton(onClick = onDelete, enabled = !pending) { Text("删除这条记忆", color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun itemEvidence(evidence: EvidenceRefResponse, onSource: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(evidence.excerpt)
            Text("来源置信度 ${(evidence.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            val submissionId = evidence.sourceId.take(36).takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
            if (submissionId != null) TextButton(onClick = { onSource(submissionId) }) { Text("查看来源对话") }
        }
    }
}

@Composable
fun InsightsPage(state: MainUiState, onRefresh: () -> Unit, onOpen: (String, Int) -> Unit) {
    WorkspaceList {
        item { PageHeader("有依据的下一步", "把本次对话、已完成的行动和有效记忆联系起来。") }
        item { TextButton(onClick = onRefresh, enabled = !state.isInsightLoading) { Text("刷新洞察") } }
        if (state.isInsightLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.insightMessage?.let { item { InfoPanel(it) } }
        if (state.insights.isEmpty() && !state.isInsightLoading) item { EmptyPanel("洞察从真实行动开始", "成功执行至少一张卡片后，这里会显示有来源的结论和建议。") }
        state.insights.forEach { bundle ->
            item(key = "date:${bundle.id}") { Text(displayDate(bundle.generatedAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(bundle.items.indices.toList(), key = { "${bundle.id}:$it" }) { index ->
                val insight = bundle.items[index]
                SummaryCard(insight.title, insight.suggestedAction ?: insight.explanation, if (insight.epistemicStatus == "fact") "事实" else "推断") { onOpen(bundle.id, index) }
            }
        }
    }
}

@Composable
fun InsightDetailPage(bundle: InsightBundleResponse, index: Int, onSource: (String) -> Unit) {
    val insight = bundle.items.getOrNull(index) ?: return
    WorkspaceList {
        item { PageHeader(insight.title, "生成于 ${displayDate(bundle.generatedAt)}") }
        item { StatusPill("${if (insight.epistemicStatus == "fact") "事实" else "推断"} · 置信度 ${(insight.confidence * 100).toInt()}%") }
        item { Text(insight.explanation, style = MaterialTheme.typography.bodyLarge) }
        insight.suggestedAction?.let { suggestion -> item { InfoPanel("建议下一步\n$suggestion${insight.suggestedAt?.let { "\n建议时间：${displayDate(it)}" }.orEmpty()}") } }
        item { SectionHeading("为什么这样建议") }
        items(insight.evidence.indices.toList()) { i -> itemEvidence(insight.evidence[i], onSource) }
        item { OutlinedButton(onClick = { onSource(bundle.submissionId) }) { Text("回到本次对话与行动") } }
        item { Text("建议不是既定事实，不会自动写入通讯录或日历。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SettingsPage(state: MainUiState, onPassword: () -> Unit, onExport: () -> Unit, onSignOut: () -> Unit, onClear: () -> Unit, onDelete: () -> Unit) {
    WorkspaceList {
        item { PageHeader("账户与设置", "管理你的账户，以及留在 ThreadMind 的数据。") }
        item { SectionHeading("账户") }
        item { SummaryCard("修改密码", "通过当前账户完成安全验证", "账户安全", onPassword) }
        item { SummaryCard("导出我的数据", "导出结构化记录，不包含原始截图", "数据副本", onExport) }
        item { OutlinedButton(onClick = onSignOut, enabled = !state.isDataOperationPending && state.pendingCardIds.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("退出登录") } }
        item { SectionHeading("数据管理") }
        item { InfoPanel("以下操作需要确认。清理 ThreadMind 数据不会删除你已经写入系统通讯录或日历的记录。") }
        item { OutlinedButton(onClick = onClear, enabled = !state.isDataOperationPending, modifier = Modifier.fillMaxWidth()) { Text("清空全部记忆", color = MaterialTheme.colorScheme.error) } }
        item { TextButton(onClick = onDelete, enabled = !state.isDataOperationPending, modifier = Modifier.fillMaxWidth()) { Text("永久删除账户", color = MaterialTheme.colorScheme.error) } }
        if (state.isDataOperationPending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.dataMessage?.let { item { InfoPanel(it) } }
    }
}

private fun MemoryTimeFilter.label() = when (this) {
    MemoryTimeFilter.ALL -> "全部时间"
    MemoryTimeFilter.LAST_30_DAYS -> "近 30 天"
    MemoryTimeFilter.LAST_YEAR -> "近一年"
}
