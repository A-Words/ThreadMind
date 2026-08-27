package app.threadmind.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthStep { EMAIL, OTP, AUTHENTICATED }

data class AuthUiState(
    val step: AuthStep = AuthStep.EMAIL,
    val email: String = "",
    val token: String = "",
    val privacyAccepted: Boolean = false,
    val isLoading: Boolean = true,
    val session: AuthSession? = null,
    val message: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.restoreSession() }
                .onSuccess { session ->
                    mutableState.update {
                        if (session == null) it.copy(isLoading = false)
                        else it.copy(step = AuthStep.AUTHENTICATED, session = session, isLoading = false)
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(isLoading = false, message = error.toUserMessage()) }
                }
        }
    }

    fun setEmail(value: String) = mutableState.update { it.copy(email = value.trim(), message = null) }
    fun setToken(value: String) = mutableState.update {
        it.copy(token = value.filter(Char::isDigit).take(6), message = null)
    }
    fun setPrivacyAccepted(value: Boolean) = mutableState.update { it.copy(privacyAccepted = value, message = null) }
    fun editEmail() = mutableState.update { it.copy(step = AuthStep.EMAIL, token = "", message = null) }

    fun requestLoginCode() = requestCode(createUser = false)

    fun requestRegistrationCode() {
        if (!state.value.privacyAccepted) {
            mutableState.update { it.copy(message = "注册前请先同意隐私与数据处理说明。") }
            return
        }
        requestCode(createUser = true)
    }

    private fun requestCode(createUser: Boolean) {
        val email = state.value.email
        if (!EMAIL_PATTERN.matches(email)) {
            mutableState.update { it.copy(message = "请输入有效的邮箱地址。") }
            return
        }
        launchRequest {
            repository.requestEmailOtp(email, createUser)
            mutableState.update { it.copy(step = AuthStep.OTP, token = "", message = "验证码已发送，请检查邮箱。") }
        }
    }

    fun verifyCode() {
        val snapshot = state.value
        if (snapshot.token.length != 6) {
            mutableState.update { it.copy(message = "请输入六位验证码。") }
            return
        }
        launchRequest {
            val session = repository.verifyEmailOtp(snapshot.email, snapshot.token)
            mutableState.update { it.copy(step = AuthStep.AUTHENTICATED, session = session, token = "", message = null) }
        }
    }

    fun signOut() = launchRequest {
        repository.signOut()
        mutableState.value = AuthUiState(isLoading = false, message = "已退出登录。")
    }

    private fun launchRequest(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, message = null) }
            runCatching { block() }
                .onFailure { error -> mutableState.update { it.copy(message = error.toUserMessage()) } }
            mutableState.update { it.copy(isLoading = false) }
        }
    }

    private fun Throwable.toUserMessage(): String = message?.takeIf { it.isNotBlank() }
        ?: "请求失败，请稍后重试。"

    private companion object {
        val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
