package app.threadmind

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.threadmind.domain.ActionCard
import app.threadmind.domain.ActionStatus
import app.threadmind.provider.ProviderExecutor
import app.threadmind.provider.ProviderResult
import app.threadmind.network.ThreadMindApi
import app.threadmind.network.MemoryRecordResponse
import app.threadmind.network.MemoryRevisionRequest
import app.threadmind.network.ActionReceiptRequest
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
    val pendingCardIds: Set<String> = emptySet(),
    val pendingReceipts: Map<String, ActionReceiptRequest> = emptyMap(),
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
            pendingReceipts = progress.pendingReceipts,
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
            runCatching { listMemories(filters) to submissions.restoreLatest() }
                .onSuccess { (response, restored) ->
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
                            pendingReceipts = restored?.pendingReceipts.orEmpty(),
                            submissionMessage = restored?.let(::progressMessage),
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
                .onSuccess { updated -> replaceCard(updated, "已保存第 ${updated.version} 版") }
                .onFailure { error -> finishCardRequest(cardId, error.message ?: "卡片保存失败") }
        }
    }

    fun confirm(cardId: String) {
        val card = state.value.cards.singleOrNull { it.id == cardId } ?: return
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
        viewModelScope.launch { recordOutcome(cardId, request, ActionStatus.CANCELLED, "未授予权限，未写入系统") }
    }

    suspend fun execute(cardId: String) {
        val card = state.value.cards.single { it.id == cardId }
        require(card.status == ActionStatus.CONFIRMED)
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
            runCatching { submissions.reportExecution(cardId, request) }
                .onSuccess {
                    mutableState.update { it.copy(pendingReceipts = it.pendingReceipts - cardId, message = "执行回执已同步") }
                }
                .onFailure { error -> mutableState.update { it.copy(message = error.message ?: "执行回执同步失败") } }
        }
    }

    private suspend fun recordOutcome(cardId: String, request: ActionReceiptRequest, status: ActionStatus, successMessage: String) {
        val report = runCatching { submissions.reportExecution(cardId, request) }
        mutableState.update { current ->
            current.copy(
                cards = current.cards.map { if (it.id == cardId) it.copy(status = status) else it },
                pendingCardIds = current.pendingCardIds - cardId,
                pendingReceipts = if (report.isSuccess) current.pendingReceipts - cardId else current.pendingReceipts + (cardId to request),
                message = if (report.isSuccess) successMessage else "$successMessage；执行回执尚未同步，请勿再次写入",
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
