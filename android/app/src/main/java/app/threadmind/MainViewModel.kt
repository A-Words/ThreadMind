package app.threadmind

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.threadmind.domain.ActionCard
import app.threadmind.domain.ActionStatus
import app.threadmind.domain.ActionType
import app.threadmind.provider.ProviderExecutor
import app.threadmind.provider.ProviderResult
import app.threadmind.provider.ContactCandidate
import app.threadmind.provider.ProviderPreflightResult
import app.threadmind.provider.ProviderTarget
import app.threadmind.network.ThreadMindApi
import app.threadmind.network.MemoryRecordResponse
import app.threadmind.network.MemoryRevisionRequest
import app.threadmind.network.ActionReceiptRequest
import app.threadmind.network.AccountExportPayload
import app.threadmind.network.InsightBundleResponse
import app.threadmind.network.ExtractionResponse
import app.threadmind.network.SubmissionProgress
import app.threadmind.network.SubmissionWorkflowRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import app.threadmind.network.SubmissionSummaryResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class MemoryTimeFilter { ALL, LAST_30_DAYS, LAST_YEAR }

data class MainUiState(
    val accountId: String? = null,
    val history: List<SubmissionSummaryResponse> = emptyList(),
    val attention: List<SubmissionSummaryResponse> = emptyList(),
    val historyView: String = "attention",
    val historyCursor: String? = null,
    val isHistoryLoading: Boolean = false,
    val historyError: String? = null,
    val readOnlyCardIds: Set<String> = emptySet(),
    val selectedImage: Uri? = null,
    val selectedImageSource: String = "in_app",
    val supplementalText: String = "",
    val submissionId: String? = null,
    val submissionStatus: String? = null,
    val isSubmissionPending: Boolean = false,
    val submissionMessage: String? = null,
    val cards: List<ActionCard> = emptyList(),
    val analysis: ExtractionResponse? = null,
    val pendingCardIds: Set<String> = emptySet(),
    val pendingReceipts: Map<String, ActionReceiptRequest> = emptyMap(),
    val providerReviewedVersions: Set<String> = emptySet(),
    val pendingProviderReviewIds: Set<String> = emptySet(),
    val providerReview: ProviderPreflightResult? = null,
    val providerTargetSelection: ProviderTargetSelection? = null,
    val message: String? = null,
    val backendStatus: BackendStatus = BackendStatus.IDLE,
    val backendMessage: String = "尚未连接服务端",
    val memories: List<MemoryRecordResponse> = emptyList(),
    val pendingMemoryIds: Set<String> = emptySet(),
    val memoryMessage: String? = null,
    val memorySearch: String = "",
    val memorySubjectRef: String = "",
    val memoryType: String? = null,
    val memoryTimeFilter: MemoryTimeFilter = MemoryTimeFilter.ALL,
    val isMemoryLoading: Boolean = false,
    val insights: List<InsightBundleResponse> = emptyList(),
    val isInsightLoading: Boolean = false,
    val insightMessage: String? = null,
    val isDataOperationPending: Boolean = false,
    val dataMessage: String? = null,
    val pendingExport: AccountExportPayload? = null,
    val accountDeleted: Boolean = false,
)

data class ProviderTargetSelection(
    val cardId: String,
    val version: Int,
    val targets: List<ProviderTarget>,
)

enum class BackendStatus { IDLE, CHECKING, CONNECTED, FAILED }

private fun progressMessage(progress: SubmissionProgress) = when (progress.status) {
    "pending_upload" -> "截图已保存在设备上，等待网络上传"
    "uploaded" -> "截图已上传，正在排队分析"
    "processing" -> "正在识别对话和行动依据…"
    "ready" -> "分析完成：${progress.cards.size} 张待审核卡片，云端原图已删除"
    "failed" -> "这次分析未能完成，请重新选择截图；云端原图将被清理"
    "deleted" -> "这条来源已删除或当前账号无法访问。"
    else -> "正在处理提交"
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val providerExecutor: ProviderExecutor,
    private val api: ThreadMindApi,
    private val submissions: SubmissionWorkflowRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private var submissionJob: Job? = null
    private var accountScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
    private var historyJob: Job? = null
    private var memoryJob: Job? = null

    fun switchAccount(accountId: String?) {
        if (state.value.accountId == accountId) return
        accountScope.cancel()
        accountScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val shared = state.value.selectedImage.takeIf { state.value.accountId == null }
        val restore = savedState.get<String>("account") == accountId
        val draftUri = if (restore) savedState.get<String>("draftUri")?.let(Uri::parse) else null
        mutableState.value = MainUiState(accountId = accountId, selectedImage = shared ?: draftUri,
            selectedImageSource = if (shared != null) "android_share" else if (restore) savedState["draftSource"] ?: "in_app" else "in_app",
            supplementalText = if (restore) savedState["draftText"] ?: "" else "",
            historyView = if (restore) savedState["historyView"] ?: "attention" else "attention",
            memorySearch = if (restore) savedState["memorySearch"] ?: "" else "",
            memorySubjectRef = if (restore) savedState["memorySubject"] ?: "" else "",
            memoryType = if (restore) savedState["memoryType"] else null,
            memoryTimeFilter = if (restore) MemoryTimeFilter.valueOf(savedState["memoryTime"] ?: "ALL") else MemoryTimeFilter.ALL,
        )
        if (!restore) savedState.keys().forEach { savedState.remove<Any>(it) }
        savedState["account"] = accountId
        if (shared != null) {
            savedState["draftUri"] = shared.toString()
            savedState["draftSource"] = "android_share"
        }
    }

    fun setHistoryView(view: String) {
        if (state.value.historyView == view) return
        savedState["historyView"] = view
        mutableState.update { it.copy(historyView = view, history = emptyList(), historyCursor = null) }
        refreshHistory()
    }

    fun refreshHistory(loadMore: Boolean = false) {
        if (loadMore && (state.value.isHistoryLoading || state.value.historyCursor == null)) return
        historyJob?.cancel()
        val view = state.value.historyView
        val cursor = if (loadMore) state.value.historyCursor else null
        mutableState.update { it.copy(isHistoryLoading = true, historyError = null) }
        historyJob = accountScope.launch {
            val local = accountRunCatching { submissions.localHistory() }.getOrDefault(emptyList())
            accountRunCatching { api.listSubmissions(view, 20, cursor) }.onSuccess { page ->
                val merged = mergeHistory(if (loadMore) state.value.history else emptyList(), page.items, local, view)
                mutableState.update { it.copy(history = merged, historyCursor = page.nextCursor, isHistoryLoading = false,
                    backendStatus = BackendStatus.CONNECTED, backendMessage = "服务端已连接",
                    attention = if (view == "attention") merged.take(3) else it.attention) }
                if (view != "attention") accountRunCatching { api.listSubmissions("attention", 3) }.onSuccess { pending ->
                    mutableState.update { it.copy(attention = mergeHistory(emptyList(), pending.items, local, "attention").take(3)) }
                }
            }.onFailure {
                mutableState.update { current -> current.copy(isHistoryLoading = false,
                    history = mergeHistory(current.history, local, local, view),
                    historyError = "暂时无法更新记录，已保留本机内容。请检查网络后重试。") }
            }
        }
    }

    fun openSubmission(id: String) {
        submissionJob?.cancel()
        savedState["submissionId"] = id
        mutableState.update { it.copy(submissionId = id, submissionStatus = null, cards = emptyList(), analysis = null,
            readOnlyCardIds = emptySet(), pendingReceipts = emptyMap(), providerReviewedVersions = emptySet(),
            providerReview = null, providerTargetSelection = null, isSubmissionPending = true, submissionMessage = "正在读取分析记录…") }
        submissionJob = accountScope.launch {
            accountRunCatching { submissions.open(id) }.onSuccess { monitorSubmission(it) }
                .onFailure { error -> finishSubmissionFailure(id, error) }
        }
    }

    fun onForeground() {
        refreshHistory()
        if (state.value.submissionId != null && state.value.selectedImage == null) refreshSubmission()
    }

    fun clearMessage() = mutableState.update { it.copy(message = null) }

    fun importImage(uri: Uri?) = selectImage(uri, "in_app")
    fun importSharedImage(uri: Uri?) = selectImage(uri, "android_share")
    fun clearSelectedImage() = selectImage(null, "in_app")
    fun setSupplementalText(value: String) { savedState["draftText"] = value; mutableState.update { it.copy(supplementalText = value) } }
    fun showCards(cards: List<ActionCard>) = mutableState.update { it.copy(cards = cards) }
    fun setMemorySearch(value: String) { savedState["memorySearch"] = value; mutableState.update { it.copy(memorySearch = value) } }
    fun setMemorySubjectRef(value: String) { savedState["memorySubject"] = value; mutableState.update { it.copy(memorySubjectRef = value) } }
    fun setMemoryType(value: String?) { savedState["memoryType"] = value; mutableState.update { it.copy(memoryType = value) } }
    fun setMemoryTimeFilter(value: MemoryTimeFilter) { savedState["memoryTime"] = value.name; mutableState.update { it.copy(memoryTimeFilter = value) } }

    private fun selectImage(uri: Uri?, source: String) {
        submissionJob?.cancel()
        savedState["draftUri"] = uri?.toString()
        savedState["draftSource"] = source
        if (uri == null) { savedState["draftText"] = ""; mutableState.update { it.copy(supplementalText = "") } }
        mutableState.update {
            it.copy(
                selectedImage = uri,
                selectedImageSource = source,
                submissionId = null,
                submissionStatus = null,
                isSubmissionPending = false,
                submissionMessage = null,
                cards = emptyList(),
                analysis = null,
                providerReviewedVersions = emptySet(),
                providerReview = null,
                providerTargetSelection = null,
            )
        }
    }

    fun submitForAnalysis() {
        val current = state.value
        if (current.isSubmissionPending) return
        val image = current.selectedImage ?: run {
            mutableState.update { it.copy(submissionMessage = "请先选择一张聊天截图") }
            return
        }
        val submissionId = UUID.randomUUID().toString()
        savedState["submissionId"] = submissionId
        submissionJob?.cancel()
        mutableState.update {
            it.copy(
                submissionId = submissionId,
                submissionStatus = "uploading",
                isSubmissionPending = true,
                submissionMessage = "正在准备上传截图…",
                cards = emptyList(),
                analysis = null,
            )
        }
        submissionJob = accountScope.launch {
            accountRunCatching {
                submissions.submit(
                    uri = image,
                    submissionId = submissionId,
                    source = current.selectedImageSource,
                    supplementalText = current.supplementalText,
                )
            }.onSuccess {
                savedState["draftUri"] = null
                savedState["draftText"] = ""
                mutableState.update { state -> state.copy(selectedImage = null, supplementalText = "") }
                refreshHistory()
                monitorSubmission(it)
            }
                .onFailure { error -> finishSubmissionFailure(submissionId, error) }
        }
    }

    fun refreshSubmission() {
        val submissionId = state.value.submissionId ?: return
        submissionJob?.cancel()
        mutableState.update { it.copy(isSubmissionPending = true, submissionMessage = "正在刷新分析状态…") }
        submissionJob = accountScope.launch {
            accountRunCatching { submissions.refresh(submissionId) }
                .onSuccess { monitorSubmission(it) }
                .onFailure { error -> finishSubmissionFailure(submissionId, error) }
        }
    }

    private suspend fun monitorSubmission(initial: SubmissionProgress) {
        var progress = initial
        applySubmission(progress)
        repeat(MAX_POLL_ATTEMPTS) {
            if (progress.status in setOf("ready", "failed", "deleted") || progress.syncMessage != null) return
            delay(POLL_INTERVAL_MILLIS)
            progress = submissions.refresh(progress.submissionId)
            applySubmission(progress)
        }
        mutableState.update { current ->
            if (current.submissionId != progress.submissionId) current else current.copy(
                isSubmissionPending = false,
                submissionMessage = "分析仍在进行，可稍后手动刷新",
            )
        }
    }

    private fun applySubmission(progress: SubmissionProgress) = mutableState.update { current ->
        if (current.submissionId != progress.submissionId) current else current.copy(
            submissionStatus = progress.status,
            isSubmissionPending = progress.status !in setOf("ready", "failed", "deleted") && progress.syncMessage == null,
            submissionMessage = progress.syncMessage ?: progressMessage(progress),
            cards = if (progress.status == "ready") progress.cards else emptyList(),
            analysis = if (progress.status == "ready") progress.analysis else null,
            pendingReceipts = progress.pendingReceipts,
            providerReviewedVersions = progress.providerReviewedVersions,
            readOnlyCardIds = if (!progress.remoteOnly) emptySet() else progress.cards.filter {
                it.status in setOf(ActionStatus.CONFIRMED, ActionStatus.EXECUTING) && it.reviewKey() !in progress.providerReviewedVersions
            }.mapTo(mutableSetOf()) { it.id },
        )
    }

    private fun finishSubmissionFailure(submissionId: String, error: Throwable) = mutableState.update { current ->
        val unavailable = error is retrofit2.HttpException && error.code() == 404
        if (current.submissionId != submissionId) current else current.copy(
            isSubmissionPending = false,
            submissionStatus = if (unavailable) "deleted" else current.submissionStatus,
            cards = if (unavailable) emptyList() else current.cards,
            analysis = if (unavailable) null else current.analysis,
            providerReview = if (unavailable) null else current.providerReview,
            providerTargetSelection = if (unavailable) null else current.providerTargetSelection,
            submissionMessage = if (unavailable) "这条来源已删除或当前账号无法访问。" else "暂时无法同步分析状态，请检查网络后刷新；不会重复上传。",
        )
    }

    fun checkBackend() {
        refreshMemories()
        refreshInsights()
        refreshHistory()
        accountScope.launch {
            accountRunCatching { submissions.restoreLatest() }.onSuccess { latest ->
                val selected: String? = savedState["submissionId"]
                if (state.value.selectedImage == null && state.value.submissionId == null) {
                    (selected ?: latest?.submissionId)?.let(::openSubmission)
                }
            }
        }
    }

    fun refreshInsights() {
        accountScope.launch {
            mutableState.update { it.copy(isInsightLoading = true, insightMessage = null) }
            accountRunCatching { api.listInsights() }
                .onSuccess { response ->
                    mutableState.update {
                        it.copy(
                            insights = response.items,
                            isInsightLoading = false,
                            insightMessage = "已刷新 ${response.items.size} 组洞察",
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isInsightLoading = false,
                            insightMessage = error.message?.takeIf(String::isNotBlank) ?: "洞察刷新失败",
                        )
                    }
                }
        }
    }

    fun refreshMemories() {
        val filters = state.value
        memoryJob?.cancel()
        memoryJob = accountScope.launch {
            mutableState.update { it.copy(isMemoryLoading = true, memoryMessage = null) }
            accountRunCatching { listMemories(filters) }
                .onSuccess { response ->
                    mutableState.update {
                        it.copy(
                            memories = response.items,
                            isMemoryLoading = false,
                            memoryMessage = "找到 ${response.items.size} 条活动记忆",
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isMemoryLoading = false,
                            memoryMessage = error.message?.takeIf(String::isNotBlank) ?: "记忆检索失败",
                        )
                    }
                }
        }
    }

    fun clearMemoryFilters() {
        savedState["memorySearch"] = ""
        savedState["memorySubject"] = ""
        savedState["memoryType"] = null
        savedState["memoryTime"] = "ALL"
        mutableState.update {
            it.copy(
                memorySearch = "",
                memorySubjectRef = "",
                memoryType = null,
                memoryTimeFilter = MemoryTimeFilter.ALL,
            )
        }
        refreshMemories()
    }

    private suspend fun listMemories(filters: MainUiState) = api.listMemories(
        search = filters.memorySearch.trim().takeIf(String::isNotEmpty),
        subjectRef = filters.memorySubjectRef.trim().takeIf(String::isNotEmpty),
        type = filters.memoryType,
        createdFrom = when (filters.memoryTimeFilter) {
            MemoryTimeFilter.ALL -> null
            MemoryTimeFilter.LAST_30_DAYS -> Instant.now().minus(30, ChronoUnit.DAYS).toString()
            MemoryTimeFilter.LAST_YEAR -> Instant.now().minus(365, ChronoUnit.DAYS).toString()
        },
    )

    fun reviseMemory(id: String, assertion: String) {
        if (assertion.isBlank()) {
            mutableState.update { it.copy(memoryMessage = "记忆内容不能为空") }
            return
        }
        accountScope.launch {
            setMemoryPending(id, true)
            accountRunCatching {
                api.reviseMemory(
                    id,
                    MemoryRevisionRequest(assertion.trim(), "user-correction:${UUID.randomUUID()}"),
                )
            }.onSuccess { revised ->
                mutableState.update { current ->
                    current.copy(
                        memories = current.memories.map { if (it.id == id) revised else it },
                        pendingMemoryIds = current.pendingMemoryIds - id,
                        backendMessage = "服务端已连接（${current.memories.size} 条记忆）",
                        memoryMessage = "已保存为第 ${revised.version} 版，并保留历史版本",
                    )
                }
            }.onFailure { error ->
                finishMemoryRequest(id, error.message ?: "记忆修订失败")
            }
        }
    }

    fun deleteMemory(id: String) {
        accountScope.launch {
            setMemoryPending(id, true)
            accountRunCatching {
                val response = api.deleteMemory(id)
                check(response.isSuccessful) { "删除失败（HTTP ${response.code()}）" }
            }.onSuccess {
                mutableState.update { current ->
                    val remaining = current.memories.filterNot { it.id == id }
                    current.copy(
                        memories = remaining,
                        pendingMemoryIds = current.pendingMemoryIds - id,
                        backendMessage = "服务端已连接（${remaining.size} 条记忆）",
                        memoryMessage = "记忆已删除",
                    )
                }
            }.onFailure { error ->
                finishMemoryRequest(id, error.message ?: "记忆删除失败")
            }
        }
    }

    fun deleteCurrentSubmission() {
        val submissionId = state.value.submissionId ?: return
        submissionJob?.cancel()
        accountScope.launch {
            mutableState.update { it.copy(isDataOperationPending = true, dataMessage = null) }
            accountRunCatching { submissions.deleteSubmission(submissionId) }.onSuccess {
                savedState["submissionId"] = null
                mutableState.update {
                    it.copy(
                        selectedImage = null,
                        selectedImageSource = "in_app",
                        supplementalText = "",
                        submissionId = null,
                        submissionStatus = null,
                        isSubmissionPending = false,
                        submissionMessage = null,
                        cards = emptyList(),
                        analysis = null,
                        pendingCardIds = emptySet(),
                        pendingReceipts = emptyMap(),
                        providerReviewedVersions = emptySet(),
                        pendingProviderReviewIds = emptySet(),
                        providerReview = null,
                        memories = emptyList(),
                        insights = it.insights.filterNot { bundle -> bundle.submissionId == submissionId },
                        isDataOperationPending = false,
                        dataMessage = "本次提交及其派生数据已删除",
                        history = it.history.filterNot { row -> row.id == submissionId },
                        attention = it.attention.filterNot { row -> row.id == submissionId },
                    )
                }
                refreshMemories()
                refreshInsights()
            }.onFailure { error -> finishDataRequest(error, "提交删除失败") }
        }
    }

    fun clearAllMemories() {
        accountScope.launch {
            mutableState.update { it.copy(isDataOperationPending = true, dataMessage = null) }
            accountRunCatching { submissions.clearMemories() }
                .onSuccess { cleared ->
                    mutableState.update {
                        it.copy(
                            memories = emptyList(),
                            isDataOperationPending = false,
                            backendMessage = "服务端已连接（0 条记忆）",
                            memoryMessage = "已清除 $cleared 条活动记忆",
                            dataMessage = "长期记忆已全部清除",
                        )
                    }
                }
                .onFailure { error -> finishDataRequest(error, "记忆清除失败") }
        }
    }

    fun requestAccountExport() {
        accountScope.launch {
            mutableState.update { it.copy(isDataOperationPending = true, dataMessage = null) }
            accountRunCatching { submissions.prepareAccountExport() }
                .onSuccess { payload ->
                    mutableState.update { it.copy(isDataOperationPending = false, pendingExport = payload) }
                }
                .onFailure { error -> finishDataRequest(error, "数据导出失败") }
        }
    }

    fun saveAccountExport(uri: Uri?) {
        val payload = state.value.pendingExport ?: return
        if (uri == null) {
            mutableState.update { it.copy(pendingExport = null, dataMessage = "已取消导出") }
            return
        }
        accountScope.launch {
            mutableState.update { it.copy(isDataOperationPending = true, dataMessage = null) }
            accountRunCatching { submissions.writeAccountExport(uri, payload) }
                .onSuccess {
                    mutableState.update {
                        it.copy(isDataOperationPending = false, pendingExport = null, dataMessage = "数据已导出")
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(pendingExport = null) }
                    finishDataRequest(error, "导出文件写入失败")
                }
        }
    }

    fun deleteAccount() {
        submissionJob?.cancel()
        accountScope.launch {
            mutableState.update { it.copy(isDataOperationPending = true, dataMessage = null) }
            accountRunCatching { submissions.deleteAccount() }
                .onSuccess { mutableState.value = MainUiState(accountDeleted = true, dataMessage = "账户及云端数据已删除") }
                .onFailure { error -> finishDataRequest(error, "账户删除失败") }
        }
    }

    fun consumeAccountDeleted() = mutableState.update { it.copy(accountDeleted = false) }

    private fun finishDataRequest(error: Throwable, fallback: String) = mutableState.update {
        it.copy(
            isDataOperationPending = false,
            dataMessage = error.message?.takeIf(String::isNotBlank) ?: fallback,
        )
    }

    private fun setMemoryPending(id: String, pending: Boolean) = mutableState.update {
        it.copy(
            pendingMemoryIds = if (pending) it.pendingMemoryIds + id else it.pendingMemoryIds - id,
            memoryMessage = null,
        )
    }

    private fun finishMemoryRequest(id: String, message: String) = mutableState.update {
        it.copy(pendingMemoryIds = it.pendingMemoryIds - id, memoryMessage = message)
    }

    fun editCard(cardId: String, fields: Map<String, String>, targetAccountId: String, resolvedIssues: Set<String>) {
        if (cardId in state.value.readOnlyCardIds) return
        val card = state.value.cards.singleOrNull { it.id == cardId } ?: return
        if (targetAccountId.isBlank()) {
            mutableState.update { it.copy(message = "必须选择目标账户") }
            return
        }
        setCardPending(cardId, true)
        accountScope.launch {
            accountRunCatching { submissions.edit(cardId, card.version, fields, targetAccountId, resolvedIssues.toList()) }
                .onSuccess { updated ->
                    invalidateProviderReview(cardId)
                    replaceCard(updated, "已保存第 ${updated.version} 版；请重新检查设备数据")
                }
                .onFailure { error -> finishCardRequest(cardId, error.message ?: "卡片保存失败") }
        }
    }

    fun confirm(cardId: String) {
        if (cardId in state.value.readOnlyCardIds) return
        val card = state.value.cards.singleOrNull { it.id == cardId } ?: return
        if (card.reviewKey() !in state.value.providerReviewedVersions) {
            mutableState.update { it.copy(message = "确认前请先检查设备中的重复项、冲突和目标账户") }
            return
        }
        setCardPending(cardId, true)
        accountScope.launch {
            accountRunCatching { submissions.confirm(card.id, card.version) }
                .onSuccess { confirmed -> replaceCard(confirmed, "已确认当前卡片版本") }
                .onFailure { error -> finishCardRequest(cardId, error.message ?: "卡片确认失败") }
        }
    }

    fun cancelCard(cardId: String) {
        if (cardId in state.value.readOnlyCardIds) return
        setCardPending(cardId, true)
        accountScope.launch {
            accountRunCatching { submissions.cancel(cardId) }
                .onSuccess {
                    mutableState.update { current ->
                        current.copy(
                            cards = current.cards.map { if (it.id == cardId) it.copy(status = ActionStatus.CANCELLED) else it },
                            pendingCardIds = current.pendingCardIds - cardId,
                            message = "卡片已取消，不会写入系统",
                        )
                    }
                }
                .onFailure { error -> finishCardRequest(cardId, error.message ?: "卡片取消失败") }
        }
    }

    fun permissionDenied(cardId: String) {
        setCardPending(cardId, true)
        val request = ActionReceiptRequest(
            receiptId = UUID.randomUUID().toString(),
            status = "cancelled",
            errorCode = "permission_denied",
            errorMessage = "用户未授予本次系统写入所需权限",
        )
        accountScope.launch { recordOutcome(cardId, request, ActionStatus.FAILED, "未授予权限，未写入系统；授权后可重新确认") }
    }

    suspend fun execute(cardId: String) {
        if (cardId in state.value.readOnlyCardIds) return
        val card = state.value.cards.single { it.id == cardId }
        require(card.status == ActionStatus.CONFIRMED)
        if (card.reviewKey() !in state.value.providerReviewedVersions) {
            mutableState.update { it.copy(message = "设备数据预检已失效，请重新检查后再执行") }
            return
        }
        if (cardId in state.value.pendingReceipts) {
            mutableState.update { it.copy(message = "执行回执尚未同步，请勿再次写入系统") }
            return
        }
        setCardPending(cardId, true)
        mutableState.update { current -> current.copy(cards = current.cards.map { if (it.id == cardId) it.copy(status = ActionStatus.EXECUTING) else it }) }
        val result = accountRunCatching { providerExecutor.execute(requireNotNull(card.confirmedSnapshot)) }
            .getOrElse { ProviderResult.Failed("provider_error", it.message ?: "Android Provider 写入失败") }
        when (result) {
            is ProviderResult.Succeeded -> recordOutcome(
                cardId,
                ActionReceiptRequest(UUID.randomUUID().toString(), "succeeded", targetRecordId = result.targetRecordId, contactContext = result.contactContext),
                ActionStatus.SUCCEEDED,
                "已写入系统，记录 ${result.targetRecordId}",
            )
            is ProviderResult.Failed -> recordOutcome(
                cardId,
                ActionReceiptRequest(UUID.randomUUID().toString(), "failed", errorCode = result.code, errorMessage = result.message),
                ActionStatus.FAILED,
                result.message,
            )
        }
    }

    fun retryReceipt(cardId: String) {
        val request = state.value.pendingReceipts[cardId] ?: return
        accountScope.launch {
            val report = accountRunCatching { submissions.reportExecution(cardId, request) }
            if (report.isSuccess) {
                val refreshed = accountRunCatching { api.listInsights() }
                mutableState.update {
                    it.copy(
                        pendingReceipts = it.pendingReceipts - cardId,
                        message = "执行回执已同步",
                        insights = refreshed.getOrNull()?.items ?: it.insights,
                        insightMessage = refreshed.exceptionOrNull()?.let { error -> error.message ?: "回执已同步，但洞察刷新失败" },
                    )
                }
            } else {
                mutableState.update { it.copy(message = report.exceptionOrNull()?.message ?: "执行回执同步失败") }
            }
        }
    }

    fun preflightProvider(cardId: String) {
        if (cardId in state.value.readOnlyCardIds) return
        val card = state.value.cards.singleOrNull { it.id == cardId } ?: return
        accountScope.launch {
            mutableState.update {
                it.copy(
                    pendingProviderReviewIds = it.pendingProviderReviewIds + cardId,
                    providerReview = null,
                    message = "正在读取设备中的相关记录…",
                )
            }
            accountRunCatching { providerExecutor.inspect(card) }
                .onSuccess { result ->
                    if (result is ProviderPreflightResult.Clear) approveProviderReview(result.cardId, result.version, "设备数据检查完成")
                    else mutableState.update {
                        it.copy(
                            pendingProviderReviewIds = it.pendingProviderReviewIds - cardId,
                            providerReview = result,
                            message = if (result is ProviderPreflightResult.Blocked) result.message else null,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            pendingProviderReviewIds = it.pendingProviderReviewIds - cardId,
                            message = error.message ?: "设备数据检查失败",
                        )
                    }
                }
        }
    }

    fun loadProviderTargets(cardId: String) {
        if (cardId in state.value.readOnlyCardIds) return
        val card = state.value.cards.singleOrNull { it.id == cardId } ?: return
        accountScope.launch {
            mutableState.update {
                it.copy(
                    pendingProviderReviewIds = it.pendingProviderReviewIds + cardId,
                    providerTargetSelection = null,
                    message = "正在读取设备可写入账户…",
                )
            }
            accountRunCatching { providerExecutor.targets(card) }
                .onSuccess { targets ->
                    mutableState.update {
                        it.copy(
                            pendingProviderReviewIds = it.pendingProviderReviewIds - cardId,
                            providerTargetSelection = if (targets.isEmpty()) null else ProviderTargetSelection(card.id, card.version, targets),
                            message = if (targets.isEmpty()) "没有找到可写入的设备账户" else null,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            pendingProviderReviewIds = it.pendingProviderReviewIds - cardId,
                            message = error.message ?: "无法读取设备账户",
                        )
                    }
                }
        }
    }

    fun selectProviderTarget(target: ProviderTarget) {
        val selection = state.value.providerTargetSelection ?: return
        val card = state.value.cards.singleOrNull { it.id == selection.cardId && it.version == selection.version } ?: return
        setCardPending(card.id, true)
        accountScope.launch {
            accountRunCatching {
                submissions.edit(
                    cardId = card.id,
                    expectedVersion = card.version,
                    fields = card.fields + target.fieldUpdates,
                    targetAccountId = target.targetAccountId,
                    resolvedValidationIssues = card.validationIssues.filter(::isTargetSelectionIssue),
                )
            }.onSuccess { updated ->
                invalidateProviderReview(card.id)
                mutableState.update { it.copy(providerTargetSelection = null) }
                replaceCard(updated, "已选择 ${target.label}；请重新检查设备数据")
            }.onFailure { error -> finishCardRequest(card.id, error.message ?: "无法保存目标账户") }
        }
    }

    fun dismissProviderTargets() = mutableState.update { it.copy(providerTargetSelection = null) }

    fun approveProviderReview() {
        val review = state.value.providerReview ?: return
        approveProviderReview(review.cardId, review.version, "已明确处理设备数据冲突")
    }

    private fun approveProviderReview(cardId: String, version: Int, message: String) {
        val card = state.value.cards.singleOrNull { it.id == cardId && it.version == version } ?: return
        accountScope.launch {
            accountRunCatching { submissions.markProviderReviewed(cardId, version) }
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            providerReviewedVersions = it.providerReviewedVersions + card.reviewKey(),
                            pendingProviderReviewIds = it.pendingProviderReviewIds - cardId,
                            providerReview = null,
                            message = message,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            pendingProviderReviewIds = it.pendingProviderReviewIds - cardId,
                            providerReview = null,
                            message = error.message ?: "无法保存设备预检结果",
                        )
                    }
                }
        }
    }

    fun convertContactToUpdate(candidate: ContactCandidate) {
        val review = state.value.providerReview as? ProviderPreflightResult.ContactCandidates ?: return
        val card = state.value.cards.singleOrNull { it.id == review.cardId && it.version == review.version } ?: return
        if (candidate.proposedChanges.isEmpty()) {
            mutableState.update { it.copy(message = "该联系人已包含拟议信息，没有可更新字段") }
            return
        }
        setCardPending(card.id, true)
        accountScope.launch {
            accountRunCatching {
                submissions.edit(
                    cardId = card.id,
                    expectedVersion = card.version,
                    fields = providerExecutor.updateFields(candidate),
                    targetAccountId = candidate.accountName ?: "local",
                    resolvedValidationIssues = card.validationIssues,
                    type = ActionType.UPDATE_CONTACT,
                )
            }.onSuccess { updated ->
                invalidateProviderReview(card.id)
                mutableState.update { it.copy(providerReview = null) }
                replaceCard(updated, "已改为更新所选联系人；请检查字段差异并重新预检")
            }.onFailure { error -> finishCardRequest(card.id, error.message ?: "无法改为更新联系人") }
        }
    }

    fun dismissProviderReview() = mutableState.update { it.copy(providerReview = null) }

    fun providerReadPermissionDenied() = mutableState.update {
        it.copy(message = "未授予读取权限，无法检查重复项或冲突，也不会执行写入")
    }

    private fun invalidateProviderReview(cardId: String) = mutableState.update {
        it.copy(providerReviewedVersions = it.providerReviewedVersions.filterNot { key -> key.startsWith("$cardId:") }.toSet())
    }

    private fun isTargetSelectionIssue(issue: String): Boolean {
        val normalized = issue.lowercase()
        return normalized.contains("target") && (normalized.contains("account") || normalized.contains("calendar"))
    }

    private suspend fun recordOutcome(cardId: String, request: ActionReceiptRequest, status: ActionStatus, successMessage: String) {
        val report = accountRunCatching { submissions.reportExecution(cardId, request) }
        val refreshed = if (report.isSuccess && status == ActionStatus.SUCCEEDED) accountRunCatching { api.listInsights() } else null
        mutableState.update { current ->
            current.copy(
                cards = current.cards.map { if (it.id == cardId) it.copy(status = status) else it },
                pendingCardIds = current.pendingCardIds - cardId,
                pendingReceipts = if (report.isSuccess) current.pendingReceipts - cardId else current.pendingReceipts + (cardId to request),
                message = if (report.isSuccess) successMessage else "$successMessage；执行回执尚未同步，请勿再次写入",
                insights = refreshed?.getOrNull()?.items ?: current.insights,
                insightMessage = refreshed?.exceptionOrNull()?.let { error -> error.message ?: "行动已完成，但洞察刷新失败" },
            )
        }
    }

    private fun setCardPending(cardId: String, pending: Boolean) = mutableState.update {
        it.copy(pendingCardIds = if (pending) it.pendingCardIds + cardId else it.pendingCardIds - cardId, message = null)
    }

    private fun replaceCard(card: ActionCard, message: String) = mutableState.update { current ->
        current.copy(
            cards = current.cards.map { if (it.id == card.id) card else it },
            pendingCardIds = current.pendingCardIds - card.id,
            message = message,
        )
    }

    private fun finishCardRequest(cardId: String, message: String) = mutableState.update {
        it.copy(pendingCardIds = it.pendingCardIds - cardId, message = message)
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L
        const val MAX_POLL_ATTEMPTS = 60
    }
}

private fun ActionCard.reviewKey() = "$id:$version"

private inline fun <T> accountRunCatching(block: () -> T): Result<T> = try { Result.success(block()) }
catch (error: CancellationException) { throw error }
catch (error: Throwable) { Result.failure(error) }

internal fun mergeHistory(
    previous: List<SubmissionSummaryResponse>,
    remote: List<SubmissionSummaryResponse>,
    local: List<SubmissionSummaryResponse>,
    view: String,
): List<SubmissionSummaryResponse> {
    // A successful cloud page is authoritative. Do not resurrect deleted or unpaged cached rows.
    val merged = (previous + remote).associateBy { it.id }.toMutableMap()
    // Only unsent uploads and unsynchronized device outcomes override cloud summaries.
    local.filter { it.status == "pending_upload" || it.hasPendingDeviceReceipt }
        .forEach { merged[it.id] = it }
    return merged.values.filter { view == "all" || it.needsAttention }
        .sortedWith(compareByDescending<SubmissionSummaryResponse> { java.time.Instant.parse(it.createdAt) }.thenByDescending { it.id })
}
