package app.threadmind

import android.net.Uri
import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class MemoryTimeFilter { ALL, LAST_30_DAYS, LAST_YEAR }

data class MainUiState(
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
    "uploaded" -> "截图已上传，等待安全分析"
    "processing" -> "正在识别对话和行动依据…"
    "ready" -> "分析完成：${progress.cards.size} 张待审核卡片，云端原图已删除"
    "failed" -> "分析失败（${progress.failureCode ?: "analysis_failed"}），云端原图已进入清理流程"
    else -> "正在处理提交"
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val providerExecutor: ProviderExecutor,
    private val api: ThreadMindApi,
    private val submissions: SubmissionWorkflowRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private var submissionJob: Job? = null

    fun importImage(uri: Uri?) = selectImage(uri, "in_app")
    fun importSharedImage(uri: Uri?) = selectImage(uri, "android_share")
    fun clearSelectedImage() = selectImage(null, "in_app")
    fun setSupplementalText(value: String) = mutableState.update { it.copy(supplementalText = value) }
    fun showCards(cards: List<ActionCard>) = mutableState.update { it.copy(cards = cards) }
    fun setMemorySearch(value: String) = mutableState.update { it.copy(memorySearch = value) }
    fun setMemorySubjectRef(value: String) = mutableState.update { it.copy(memorySubjectRef = value) }
    fun setMemoryType(value: String?) = mutableState.update { it.copy(memoryType = value) }
    fun setMemoryTimeFilter(value: MemoryTimeFilter) = mutableState.update { it.copy(memoryTimeFilter = value) }

    private fun selectImage(uri: Uri?, source: String) {
        submissionJob?.cancel()
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
        val image = current.selectedImage ?: run {
            mutableState.update { it.copy(submissionMessage = "请先选择一张聊天截图") }
            return
        }
        val submissionId = UUID.randomUUID().toString()
        submissionJob?.cancel()
        mutableState.update {
            it.copy(
                submissionId = submissionId,
                submissionStatus = "uploading",
                isSubmissionPending = true,
                submissionMessage = "正在加密上传截图…",
                cards = emptyList(),
                analysis = null,
            )
        }
        submissionJob = viewModelScope.launch {
            runCatching {
                submissions.submit(
                    uri = image,
                    submissionId = submissionId,
                    source = current.selectedImageSource,
                    supplementalText = current.supplementalText,
                )
            }.onSuccess { monitorSubmission(it) }
                .onFailure { error -> finishSubmissionFailure(submissionId, error) }
        }
    }

    fun refreshSubmission() {
        val submissionId = state.value.submissionId ?: return
        submissionJob?.cancel()
        mutableState.update { it.copy(isSubmissionPending = true, submissionMessage = "正在刷新分析状态…") }
        submissionJob = viewModelScope.launch {
            runCatching { submissions.refresh(submissionId) }
                .onSuccess { monitorSubmission(it) }
                .onFailure { error -> finishSubmissionFailure(submissionId, error) }
        }
    }

    private suspend fun monitorSubmission(initial: SubmissionProgress) {
        var progress = initial
        if (progress.status == "ready" && progress.cards.isEmpty()) progress = submissions.refresh(progress.submissionId)
        applySubmission(progress)
        repeat(MAX_POLL_ATTEMPTS) {
            if (progress.status == "ready" || progress.status == "failed") return
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
            isSubmissionPending = progress.status !in setOf("ready", "failed"),
            submissionMessage = progressMessage(progress),
            cards = if (progress.status == "ready") progress.cards else current.cards,
            analysis = if (progress.status == "ready") progress.analysis else current.analysis,
            pendingReceipts = progress.pendingReceipts,
            providerReviewedVersions = progress.providerReviewedVersions,
        )
    }

    private fun finishSubmissionFailure(submissionId: String, error: Throwable) = mutableState.update { current ->
        if (current.submissionId != submissionId) current else current.copy(
            isSubmissionPending = false,
            submissionMessage = error.message?.takeIf(String::isNotBlank) ?: "提交处理失败",
        )
    }

    fun checkBackend() {
        val filters = state.value
        viewModelScope.launch {
            mutableState.update {
                it.copy(backendStatus = BackendStatus.CHECKING, backendMessage = "正在验证服务端身份…")
            }
            runCatching {
                Triple(listMemories(filters), submissions.restoreLatest(), api.listInsights())
            }.onSuccess { (response, restored, insightResponse) ->
                    mutableState.update {
                        it.copy(
                            backendStatus = BackendStatus.CONNECTED,
                            backendMessage = "服务端已连接（${response.items.size} 条记忆）",
                            memories = response.items,
                            memoryMessage = null,
                            submissionId = restored?.submissionId,
                            submissionStatus = restored?.status,
                            isSubmissionPending = restored != null && restored.status !in setOf("ready", "failed"),
                            cards = restored?.cards.orEmpty(),
                            analysis = restored?.analysis,
                            pendingReceipts = restored?.pendingReceipts.orEmpty(),
                            providerReviewedVersions = restored?.providerReviewedVersions.orEmpty(),
                            providerReview = null,
                            submissionMessage = restored?.let(::progressMessage),
                            insights = insightResponse.items,
                            insightMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            backendStatus = BackendStatus.FAILED,
                            backendMessage = error.message?.takeIf(String::isNotBlank) ?: "服务端连接失败",
                        )
                    }
                }
        }
    }

    fun refreshInsights() {
        viewModelScope.launch {
            mutableState.update { it.copy(isInsightLoading = true, insightMessage = null) }
            runCatching { api.listInsights() }
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
        viewModelScope.launch {
            mutableState.update { it.copy(isMemoryLoading = true, memoryMessage = null) }
            runCatching { listMemories(filters) }
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
        viewModelScope.launch {
            setMemoryPending(id, true)
            runCatching {
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
        viewModelScope.launch {
            setMemoryPending(id, true)
            runCatching {
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
        viewModelScope.launch {
            mutableState.update { it.copy(isDataOperationPending = true, dataMessage = null) }
            runCatching {
                submissions.deleteSubmission(submissionId)
                listMemories(state.value) to api.listInsights()
            }.onSuccess { (memoryResponse, insightResponse) ->
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
                        memories = memoryResponse.items,
                        insights = insightResponse.items,
                        isDataOperationPending = false,
                        dataMessage = "本次提交及其派生数据已删除",
                    )
                }
            }.onFailure { error -> finishDataRequest(error, "提交删除失败") }
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            mutableState.update { it.copy(isDataOperationPending = true, dataMessage = null) }
            runCatching { submissions.clearMemories() }
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
        viewModelScope.launch {
            mutableState.update { it.copy(isDataOperationPending = true, dataMessage = null) }
            runCatching { submissions.prepareAccountExport() }
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
        viewModelScope.launch {
            mutableState.update { it.copy(isDataOperationPending = true, dataMessage = null) }
            runCatching { submissions.writeAccountExport(uri, payload) }
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
        viewModelScope.launch {
            mutableState.update { it.copy(isDataOperationPending = true, dataMessage = null) }
            runCatching { submissions.deleteAccount() }
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
        val card = state.value.cards.singleOrNull { it.id == cardId } ?: return
        if (targetAccountId.isBlank()) {
            mutableState.update { it.copy(message = "必须选择目标账户") }
            return
        }
        setCardPending(cardId, true)
        viewModelScope.launch {
            runCatching { submissions.edit(cardId, card.version, fields, targetAccountId, resolvedIssues.toList()) }
                .onSuccess { updated ->
                    invalidateProviderReview(cardId)
                    replaceCard(updated, "已保存第 ${updated.version} 版；请重新检查设备数据")
                }
                .onFailure { error -> finishCardRequest(cardId, error.message ?: "卡片保存失败") }
        }
    }

    fun confirm(cardId: String) {
        val card = state.value.cards.singleOrNull { it.id == cardId } ?: return
        if (card.reviewKey() !in state.value.providerReviewedVersions) {
            mutableState.update { it.copy(message = "确认前请先检查设备中的重复项、冲突和目标账户") }
            return
        }
        setCardPending(cardId, true)
        viewModelScope.launch {
            runCatching { submissions.confirm(card.id, card.version) }
                .onSuccess { confirmed -> replaceCard(confirmed, "已确认当前卡片版本") }
                .onFailure { error -> finishCardRequest(cardId, error.message ?: "卡片确认失败") }
        }
    }

    fun cancelCard(cardId: String) {
        setCardPending(cardId, true)
        viewModelScope.launch {
            runCatching { submissions.cancel(cardId) }
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
        viewModelScope.launch { recordOutcome(cardId, request, ActionStatus.FAILED, "未授予权限，未写入系统；授权后可重新确认") }
    }

    suspend fun execute(cardId: String) {
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
        val result = runCatching { providerExecutor.execute(requireNotNull(card.confirmedSnapshot)) }
            .getOrElse { ProviderResult.Failed("provider_error", it.message ?: "Android Provider 写入失败") }
        when (result) {
            is ProviderResult.Succeeded -> recordOutcome(
                cardId,
                ActionReceiptRequest(UUID.randomUUID().toString(), "succeeded", targetRecordId = result.targetRecordId),
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
        viewModelScope.launch {
            val report = runCatching { submissions.reportExecution(cardId, request) }
            if (report.isSuccess) {
                val refreshed = runCatching { api.listInsights() }
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
        val card = state.value.cards.singleOrNull { it.id == cardId } ?: return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    pendingProviderReviewIds = it.pendingProviderReviewIds + cardId,
                    providerReview = null,
                    message = "正在读取设备中的相关记录…",
                )
            }
            runCatching { providerExecutor.inspect(card) }
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
        val card = state.value.cards.singleOrNull { it.id == cardId } ?: return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    pendingProviderReviewIds = it.pendingProviderReviewIds + cardId,
                    providerTargetSelection = null,
                    message = "正在读取设备可写入账户…",
                )
            }
            runCatching { providerExecutor.targets(card) }
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
        viewModelScope.launch {
            runCatching {
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
        viewModelScope.launch {
            runCatching { submissions.markProviderReviewed(cardId, version) }
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
        viewModelScope.launch {
            runCatching {
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
        val report = runCatching { submissions.reportExecution(cardId, request) }
        val refreshed = if (report.isSuccess && status == ActionStatus.SUCCEEDED) runCatching { api.listInsights() } else null
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
