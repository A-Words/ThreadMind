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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val selectedImage: Uri? = null,
    val supplementalText: String = "",
    val cards: List<ActionCard> = emptyList(),
    val message: String? = null,
    val backendStatus: BackendStatus = BackendStatus.IDLE,
    val backendMessage: String = "尚未连接服务端",
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
