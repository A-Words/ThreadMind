package app.threadmind

import android.net.Uri
import androidx.lifecycle.ViewModel
import app.threadmind.domain.ActionCard
import app.threadmind.domain.ActionCardPolicy
import app.threadmind.domain.ActionStatus
import app.threadmind.provider.ProviderExecutor
import app.threadmind.provider.ProviderResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class MainUiState(
    val selectedImage: Uri? = null,
    val supplementalText: String = "",
    val cards: List<ActionCard> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val providerExecutor: ProviderExecutor,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    fun importImage(uri: Uri?) = mutableState.update { it.copy(selectedImage = uri) }
    fun setSupplementalText(value: String) = mutableState.update { it.copy(supplementalText = value) }
    fun showCards(cards: List<ActionCard>) = mutableState.update { it.copy(cards = cards) }

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
