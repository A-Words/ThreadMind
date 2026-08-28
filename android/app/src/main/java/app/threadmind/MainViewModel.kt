package app.threadmind

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.threadmind.domain.ActionCard
import app.threadmind.domain.ActionCardPolicy
import app.threadmind.domain.ActionStatus
import app.threadmind.provider.ProviderExecutor
import app.threadmind.provider.ProviderResult
import app.threadmind.network.ThreadMindApi
import app.threadmind.network.MemoryRecordResponse
import app.threadmind.network.MemoryRevisionRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID

data class MainUiState(
    val selectedImage: Uri? = null,
    val supplementalText: String = "",
    val cards: List<ActionCard> = emptyList(),
    val message: String? = null,
    val backendStatus: BackendStatus = BackendStatus.IDLE,
    val backendMessage: String = "尚未连接服务端",
    val memories: List<MemoryRecordResponse> = emptyList(),
    val pendingMemoryIds: Set<String> = emptySet(),
    val memoryMessage: String? = null,
)

enum class BackendStatus { IDLE, CHECKING, CONNECTED, FAILED }

@HiltViewModel
class MainViewModel @Inject constructor(
    private val providerExecutor: ProviderExecutor,
    private val api: ThreadMindApi,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    fun importImage(uri: Uri?) = mutableState.update { it.copy(selectedImage = uri) }
    fun setSupplementalText(value: String) = mutableState.update { it.copy(supplementalText = value) }
    fun showCards(cards: List<ActionCard>) = mutableState.update { it.copy(cards = cards) }

    fun checkBackend() {
        viewModelScope.launch {
            mutableState.update {
                it.copy(backendStatus = BackendStatus.CHECKING, backendMessage = "正在验证服务端身份…")
            }
            runCatching { api.listMemories() }
                .onSuccess { response ->
                    mutableState.update {
                        it.copy(
                            backendStatus = BackendStatus.CONNECTED,
                            backendMessage = "服务端已连接（${response.items.size} 条记忆）",
                            memories = response.items,
                            memoryMessage = null,
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

    fun confirm(cardId: String) = mutableState.update { state ->
        state.copy(cards = state.cards.map { if (it.id == cardId) ActionCardPolicy.confirm(it) else it })
    }

    suspend fun execute(cardId: String) {
        val card = state.value.cards.single { it.id == cardId }
        require(card.status == ActionStatus.CONFIRMED)
        val result = providerExecutor.execute(requireNotNull(card.confirmedSnapshot))
        mutableState.update { current ->
            current.copy(
                cards = current.cards.map {
                    if (it.id != cardId) it else it.copy(status = if (result is ProviderResult.Succeeded) ActionStatus.SUCCEEDED else ActionStatus.FAILED)
                },
                message = when (result) {
                    is ProviderResult.Succeeded -> "已写入系统，记录 ${result.targetRecordId}"
                    is ProviderResult.Failed -> result.message
                },
            )
        }
    }
}
