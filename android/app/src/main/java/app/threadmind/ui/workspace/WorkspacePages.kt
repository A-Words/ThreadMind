package app.threadmind.ui.workspace

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.threadmind.MainUiState
import app.threadmind.MainViewModel
import app.threadmind.MemoryTimeFilter
import app.threadmind.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OverviewPage(state: MainUiState, onNew: () -> Unit, onSubmission: (String) -> Unit, onInsight: (String, Int) -> Unit,
                 onActions: () -> Unit, onInsights: () -> Unit, onRefresh: () -> Unit) {
    WorkspaceList {
        item { PageHeader("对话之后，还有下一步", "把重要的约定留住，让每一步都有依据。") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("从一张聊天截图开始", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("识别约定与行动，由你核对和确认。分析完成后删除云端原图。",
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Button(onClick = onNew) { Text("开始分析") }
                }
            }
        }
        state.historyError?.let { item { InfoPanel(it, "重试", onRefresh, error = true) } }
        item { SectionHeading("值得关注", "全部洞察", onInsights) }
        val insightItems = state.insights.flatMap { bundle -> bundle.items.mapIndexed { index, item -> Triple(bundle.id, index, item) } }.take(3)
        if (state.isInsightLoading && insightItems.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (insightItems.isEmpty() && !state.isInsightLoading) item {
            EmptyPanel("让建议建立在真实行动上", "成功完成一张行动卡后，这里会出现有依据的洞察与下一步建议。")
        }
        items(insightItems, key = { "${it.first}:${it.second}" }) { (bundleId, index, insight) ->
            SummaryCard(insight.title, insight.suggestedAction ?: insight.explanation,
                if (insight.epistemicStatus == "fact") "事实 · 有来源依据" else "推断 · 需要你的判断") { onInsight(bundleId, index) }
        }
        item { SectionHeading("待你处理", "全部行动", onActions) }
        if (state.isHistoryLoading && state.attention.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.attention.isEmpty() && !state.isHistoryLoading) item {
            EmptyPanel("暂时没有待处理任务", "新的分析任务和待确认行动会出现在这里。")
        }
        items(state.attention.take(3), key = { "pending:${it.id}" }) { row -> SubmissionSummaryCard(row) { onSubmission(row.id) } }
    }
}

@Composable
fun SubmissionSummaryCard(row: SubmissionSummaryResponse, onClick: () -> Unit) {
    val count = row.actionCounts.values.sum()
    val pending = row.actionCounts.filterKeys { it in setOf("draft", "blocked", "ready", "confirmed", "executing") }.values.sum()
    SummaryCard("${displayDate(row.createdAt)} 的对话", when {
        row.status == "pending_upload" -> "已保存在本机，等待网络恢复后上传"
        row.status in setOf("uploaded", "processing") -> "可以离开此页，任务会继续处理"
        row.status == "failed" -> "这次分析未完成，打开查看后续操作"
        pending > 0 -> "$count 张行动卡 · $pending 张需要处理"
        count == 0 -> "分析已完成，没有需要执行的行动"
        else -> "$count 张行动卡 · 查看处理结果"
    }, submissionLabel(row.status), onClick)
}

@Composable
fun HistoryPage(state: MainUiState, onFilter: (String) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, onOpen: (String) -> Unit) {
    WorkspaceList {
        item { PageHeader("行动与记录", "每一次对话，都可以回来继续。") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.historyView == "attention", onClick = { onFilter("attention") }, label = { Text("待处理") })
                FilterChip(selected = state.historyView == "all", onClick = { onFilter("all") }, label = { Text("全部记录") })
                TextButton(onClick = onRefresh, enabled = !state.isHistoryLoading) { Text("刷新") }
            }
        }
        state.historyError?.let { item { InfoPanel(it, "重试", onRefresh, error = true) } }
        if (state.isHistoryLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.history.isEmpty() && !state.isHistoryLoading) item {
            EmptyPanel(if (state.historyView == "attention") "没有待处理的行动" else "还没有分析记录", "从一张截图开始，分析结果会保存在这里。")
        }
        items(state.history, key = { it.id }) { row -> SubmissionSummaryCard(row) { onOpen(row.id) } }
        if (state.historyCursor != null) item { OutlinedButton(onClick = onMore, enabled = !state.isHistoryLoading, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
    }
}

@Composable
fun NewAnalysisPage(state: MainUiState, onPick: () -> Unit, onText: (String) -> Unit, onUpload: () -> Unit, onCancel: () -> Unit, onDirty: (Boolean) -> Unit) {
    var text by rememberSaveable { mutableStateOf(state.supplementalText) }
    LaunchedEffect(text) { onText(text) }
    LaunchedEffect(state.selectedImage, text) { onDirty(state.selectedImage != null || text.isNotBlank()) }
    WorkspaceList {
        item { PageHeader("留住这段对话", "选择聊天截图，补充你认为重要的背景。") }
        item {
            if (state.selectedImage != null) ScreenshotPreview(state.selectedImage)
            else EmptyPanel("选择一张聊天截图", "支持 PNG、JPEG 和 WebP，最大 15 MB。", "选择图片", onPick)
        }
        if (state.selectedImage != null) item { TextButton(onClick = onPick, enabled = !state.isSubmissionPending) { Text("更换截图") } }
        item { OutlinedTextField(value = text, onValueChange = { text = it.take(4000) }, label = { Text("补充说明（可选）") },
            placeholder = { Text("例如：这是与同事讨论下周会议的对话") }, minLines = 3, enabled = !state.isSubmissionPending,
            modifier = Modifier.fillMaxWidth().testTag("analysis_context")) }
        item { InfoPanel("点击下方按钮后，截图与补充说明将上传到云端模型分析。处理完成后删除云端原图，保留结构化结果；写入通讯录或日历前仍需你确认。") }
        state.submissionMessage?.let { item { InfoPanel(it) } }
        item { Button(onClick = { onText(text); onUpload() }, enabled = state.selectedImage != null && !state.isSubmissionPending,
            modifier = Modifier.fillMaxWidth().testTag("upload_analysis")) { Text("同意上传并分析") } }
        item { TextButton(onClick = onCancel, enabled = !state.isSubmissionPending, modifier = Modifier.fillMaxWidth()) { Text("取消") } }
    }
}

@Composable
private fun ScreenshotPreview(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1200).coerceAtLeast(1) }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
        }
    }
    Card(Modifier.fillMaxWidth()) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "所选聊天截图预览", Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp), contentScale = ContentScale.Fit)
        else Text("图片预览暂不可用，可重新选择图片", Modifier.padding(24.dp))
    }
}

@Composable
fun SubmissionPage(state: MainUiState, onRefresh: () -> Unit, onNew: () -> Unit, onCard: (String) -> Unit, onDelete: () -> Unit) {
    var transcript by rememberSaveable(state.submissionId) { mutableStateOf(false) }
    WorkspaceList {
        item { PageHeader("从理解到行动", "先核对重要信息，再决定是否写入系统。") }
        item { StatusPill(submissionLabel(state.submissionStatus), warning = state.submissionStatus == "failed") }
        if (state.isSubmissionPending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.submissionMessage?.let { item { InfoPanel(it, "刷新状态", onRefresh) } }
        if (state.submissionStatus == "failed") item { FilledTonalButton(onClick = onNew) { Text("重新选择截图") } }
        if (state.submissionStatus == "ready") {
            item { SectionHeading("待审核行动") }
            if (state.cards.isEmpty()) item { EmptyPanel("没有需要执行的行动", "你仍然可以核对下方转录。系统不会为了生成卡片而虚构约定。") }
            items(state.cards, key = { it.id }) { card ->
                SummaryCard(card.title(), if (card.id in state.pendingReceipts) "系统结果已保存，等待同步回执" else
                    if (card.blockers.isNotEmpty()) "有 ${card.blockers.size} 项需要核对或补充" else "查看详情与来源依据",
                    "${card.type.label()} · ${card.status.label()}") { onCard(card.id) }
            }
            state.analysis?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings -> item { InfoPanel("需要留意\n${warnings.joinToString("\n")}") } }
            item { OutlinedButton(onClick = { transcript = !transcript }, modifier = Modifier.fillMaxWidth()) { Text(if (transcript) "收起对话转录" else "查看对话转录与依据") } }
            if (transcript) {
                items(state.analysis?.messages.orEmpty().sortedBy { it.order }, key = { "message:${it.id}" }) { message ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message.speaker ?: "说话人待确认", style = MaterialTheme.typography.labelLarge)
                            Text(message.text)
                            Text("置信度 ${(message.confidence * 100).toInt()}%${if (message.confidence < .8) " · 请重点核对" else ""}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        if (state.submissionStatus != null) item { TextButton(onClick = onDelete, enabled = !state.isDataOperationPending) { Text("删除这次分析", color = MaterialTheme.colorScheme.error) } }
    }
}
