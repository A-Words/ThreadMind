package app.threadmind.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthStep { CREDENTIALS, CODE, NEW_PASSWORD, AUTHENTICATED }
enum class AuthIntent { LOGIN, REGISTER }
enum class AuthMethod { PASSWORD, OTP }
enum class AuthCodePurpose {
    PASSWORDLESS_LOGIN,
    PASSWORDLESS_REGISTRATION,
    SIGNUP_CONFIRMATION,
    PASSWORD_RECOVERY,
    ACCOUNT_PASSWORD_UPDATE,
}

enum class AuthFeedbackKind { INFO, SUCCESS, ERROR }

data class AuthFeedback(val message: String, val kind: AuthFeedbackKind)

data class AuthFieldErrors(
    val email: String? = null,
    val password: String? = null,
    val confirmPassword: String? = null,
    val token: String? = null,
    val privacy: String? = null,
) {
    val hasAny: Boolean
        get() = email != null || password != null || confirmPassword != null || token != null || privacy != null
}

data class AuthUiState(
    val step: AuthStep = AuthStep.CREDENTIALS,
    val intent: AuthIntent = AuthIntent.LOGIN,
    val method: AuthMethod = AuthMethod.PASSWORD,
    val codePurpose: AuthCodePurpose? = null,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val token: String = "",
    val privacyAccepted: Boolean = false,
    val isInitializing: Boolean = true,
    val isLoading: Boolean = false,
    val isResendingCode: Boolean = false,
    val session: AuthSession? = null,
    val fieldErrors: AuthFieldErrors = AuthFieldErrors(),
    val feedback: AuthFeedback? = null,
    val codeDeliveryNotice: String? = null,
    val resendSecondsRemaining: Int = 0,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()
    private var resendCountdownJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { repository.restoreSession() }
                .onSuccess { session ->
                    mutableState.update {
                        if (session == null) it.copy(isInitializing = false)
                        else it.copy(step = AuthStep.AUTHENTICATED, session = session, isInitializing = false)
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isInitializing = false,
                            feedback = AuthFeedback(error.toUserMessage(), AuthFeedbackKind.ERROR),
                        )
                    }
                }
        }
    }

    fun setEmail(value: String) = mutableState.update {
        it.copy(email = value.trim(), fieldErrors = it.fieldErrors.copy(email = null), feedback = null)
    }

    fun setPassword(value: String) = mutableState.update {
        it.copy(password = value, fieldErrors = it.fieldErrors.copy(password = null), feedback = null)
    }

    fun setConfirmPassword(value: String) = mutableState.update {
        it.copy(
            confirmPassword = value,
            fieldErrors = it.fieldErrors.copy(confirmPassword = null),
            feedback = null,
        )
    }

    fun setToken(value: String) = mutableState.update {
        it.copy(
            token = value.filter(Char::isDigit).take(CODE_LENGTH),
            fieldErrors = it.fieldErrors.copy(token = null),
            feedback = null,
        )
    }

    fun setPrivacyAccepted(value: Boolean) = mutableState.update {
        it.copy(
            privacyAccepted = value,
            fieldErrors = it.fieldErrors.copy(privacy = null),
            feedback = null,
        )
    }

    fun setIntent(intent: AuthIntent) = mutableState.update {
        it.copy(
            step = AuthStep.CREDENTIALS,
            intent = intent,
            method = AuthMethod.PASSWORD,
            codePurpose = null,
            password = "",
            confirmPassword = "",
            token = "",
            privacyAccepted = false,
            fieldErrors = AuthFieldErrors(),
            feedback = null,
            codeDeliveryNotice = null,
            resendSecondsRemaining = 0,
        )
    }

    fun setMethod(method: AuthMethod) = mutableState.update {
        it.copy(
            step = AuthStep.CREDENTIALS,
            method = method,
            codePurpose = null,
            password = "",
            confirmPassword = "",
            token = "",
            fieldErrors = AuthFieldErrors(),
            feedback = null,
            codeDeliveryNotice = null,
            resendSecondsRemaining = 0,
        )
    }

    fun clearFeedback() = mutableState.update { it.copy(feedback = null) }

    fun submitCredentials() {
        val snapshot = state.value
        val errors = validateCredentials(snapshot)
        if (errors.hasAny) {
            mutableState.update { it.copy(fieldErrors = errors, feedback = null) }
            return
        }
        when (snapshot.intent to snapshot.method) {
            AuthIntent.LOGIN to AuthMethod.PASSWORD -> signInWithPassword(snapshot)
            AuthIntent.LOGIN to AuthMethod.OTP -> requestPasswordlessCode(snapshot, createUser = false)
            AuthIntent.REGISTER to AuthMethod.PASSWORD -> signUpWithPassword(snapshot)
            AuthIntent.REGISTER to AuthMethod.OTP -> requestPasswordlessCode(snapshot, createUser = true)
        }
    }

    private fun validateCredentials(snapshot: AuthUiState): AuthFieldErrors {
        val passwordRegistration = snapshot.intent == AuthIntent.REGISTER && snapshot.method == AuthMethod.PASSWORD
        return AuthFieldErrors(
            email = if (EMAIL_PATTERN.matches(snapshot.email)) null else "请输入有效的邮箱地址。",
            password = when {
                snapshot.method != AuthMethod.PASSWORD -> null
                snapshot.intent == AuthIntent.LOGIN && snapshot.password.isBlank() -> "请输入密码。"
                passwordRegistration && snapshot.password.length < MIN_PASSWORD_LENGTH -> "密码至少需要八位。"
                else -> null
            },
            confirmPassword = when {
                !passwordRegistration -> null
                snapshot.confirmPassword.isBlank() -> "请再次输入密码。"
                snapshot.password != snapshot.confirmPassword -> "两次输入的密码不一致。"
                else -> null
            },
            privacy = if (snapshot.intent == AuthIntent.REGISTER && !snapshot.privacyAccepted) {
                "注册前请先同意隐私与数据处理说明。"
            } else {
                null
            },
        )
    }

    private fun signInWithPassword(snapshot: AuthUiState) = launchRequest {
        completeAuthentication(repository.signInWithPassword(snapshot.email, snapshot.password))
    }

    private fun signUpWithPassword(snapshot: AuthUiState) = launchRequest {
        val session = repository.signUpWithPassword(snapshot.email, snapshot.password)
        if (session != null) completeAuthentication(session)
        else showCodeStep(AuthCodePurpose.SIGNUP_CONFIRMATION, "确认码已发送，请检查邮箱。")
    }

    private fun requestPasswordlessCode(snapshot: AuthUiState, createUser: Boolean) = launchRequest {
        repository.requestEmailOtp(snapshot.email, createUser)
        showCodeStep(
            if (createUser) AuthCodePurpose.PASSWORDLESS_REGISTRATION else AuthCodePurpose.PASSWORDLESS_LOGIN,
            "验证码已发送，请检查邮箱。",
        )
    }

    fun startPasswordRecovery() {
        val email = state.value.email
        if (!EMAIL_PATTERN.matches(email)) {
            mutableState.update {
                it.copy(
                    fieldErrors = it.fieldErrors.copy(email = "请先输入有效的邮箱地址。"),
                    feedback = null,
                )
            }
            return
        }
        requestRecoveryCode(email, AuthCodePurpose.PASSWORD_RECOVERY)
    }

    fun beginPasswordUpdate() {
        val email = state.value.session?.email
        if (email.isNullOrBlank()) {
            mutableState.update {
                it.copy(feedback = AuthFeedback("当前账户没有可验证的邮箱。", AuthFeedbackKind.ERROR))
            }
            return
        }
        requestRecoveryCode(email, AuthCodePurpose.ACCOUNT_PASSWORD_UPDATE)
    }

    private fun requestRecoveryCode(email: String, purpose: AuthCodePurpose) = launchRequest {
        repository.requestPasswordRecovery(email)
        mutableState.update {
            it.copy(
                step = AuthStep.CODE,
                intent = AuthIntent.LOGIN,
                method = AuthMethod.PASSWORD,
                codePurpose = purpose,
                email = email,
                password = "",
                confirmPassword = "",
                token = "",
                fieldErrors = AuthFieldErrors(),
                feedback = null,
                codeDeliveryNotice = "若账号存在，恢复码已发送，请检查邮箱。",
                resendSecondsRemaining = RESEND_DELAY_SECONDS,
            )
        }
        startResendCountdown()
    }

    fun resendCode() {
        val snapshot = state.value
        val purpose = snapshot.codePurpose ?: return
        if (snapshot.isLoading || snapshot.isResendingCode || snapshot.resendSecondsRemaining > 0) return
        viewModelScope.launch {
            mutableState.update { it.copy(isResendingCode = true, feedback = null) }
            runCatching {
                repository.resendEmailOtp(snapshot.email, purpose.toRepositoryPurpose())
            }.onSuccess {
                mutableState.update {
                    it.copy(
                        token = "",
                        fieldErrors = it.fieldErrors.copy(token = null),
                        codeDeliveryNotice = resendFeedback(purpose),
                        resendSecondsRemaining = RESEND_DELAY_SECONDS,
                    )
                }
                startResendCountdown()
            }.onFailure { error ->
                mutableState.update {
                    it.copy(feedback = AuthFeedback(error.toUserMessage(), AuthFeedbackKind.ERROR))
                }
            }
            mutableState.update { it.copy(isResendingCode = false) }
        }
    }

    fun verifyCode() {
        val snapshot = state.value
        val purpose = snapshot.codePurpose ?: return
        if (snapshot.token.length != CODE_LENGTH) {
            mutableState.update {
                it.copy(fieldErrors = it.fieldErrors.copy(token = "请输入六位验证码。"), feedback = null)
            }
            return
        }
        launchRequest {
            val session = repository.verifyEmailOtp(
                snapshot.email,
                snapshot.token,
                purpose.toRepositoryPurpose(),
            )
            if (purpose == AuthCodePurpose.PASSWORD_RECOVERY || purpose == AuthCodePurpose.ACCOUNT_PASSWORD_UPDATE) {
                mutableState.update {
                    it.copy(
                        step = AuthStep.NEW_PASSWORD,
                        session = session,
                        token = "",
                        password = "",
                        confirmPassword = "",
                        fieldErrors = AuthFieldErrors(),
                        feedback = null,
                        codeDeliveryNotice = null,
                        resendSecondsRemaining = 0,
                    )
                }
            } else {
                completeAuthentication(session)
            }
        }
    }

    fun updatePassword() {
        val snapshot = state.value
        val errors = validateNewPassword(snapshot)
        if (errors.hasAny) {
            mutableState.update { it.copy(fieldErrors = errors, feedback = null) }
            return
        }
        launchRequest {
            completeAuthentication(
                repository.updatePassword(snapshot.password),
                AuthFeedback("密码已更新。", AuthFeedbackKind.SUCCESS),
            )
        }
    }

    fun cancelFlow() {
        val snapshot = state.value
        resendCountdownJob?.cancel()
        if (snapshot.codePurpose == AuthCodePurpose.ACCOUNT_PASSWORD_UPDATE && snapshot.session != null) {
            mutableState.update {
                it.copy(
                    step = AuthStep.AUTHENTICATED,
                    codePurpose = null,
                    password = "",
                    confirmPassword = "",
                    token = "",
                    fieldErrors = AuthFieldErrors(),
                    feedback = null,
                    codeDeliveryNotice = null,
                    resendSecondsRemaining = 0,
                )
            }
            return
        }
        if (
            snapshot.codePurpose == AuthCodePurpose.PASSWORD_RECOVERY &&
            snapshot.step == AuthStep.NEW_PASSWORD &&
            snapshot.session != null
        ) {
            launchRequest {
                repository.signOut()
                mutableState.value = AuthUiState(email = snapshot.email, isInitializing = false)
            }
            return
        }
        mutableState.update {
            val returningFromRecovery = snapshot.codePurpose == AuthCodePurpose.PASSWORD_RECOVERY
            it.copy(
                step = AuthStep.CREDENTIALS,
                intent = if (returningFromRecovery) AuthIntent.LOGIN else snapshot.intent,
                method = if (returningFromRecovery) AuthMethod.PASSWORD else snapshot.method,
                codePurpose = null,
                password = "",
                confirmPassword = "",
                token = "",
                session = null,
                fieldErrors = AuthFieldErrors(),
                feedback = null,
                codeDeliveryNotice = null,
                resendSecondsRemaining = 0,
            )
        }
    }

    fun signOut() = launchRequest {
        repository.signOut()
        mutableState.value = AuthUiState(
            isInitializing = false,
            feedback = AuthFeedback("已退出登录。", AuthFeedbackKind.INFO),
        )
    }

    fun accountDeleted() {
        mutableState.value = AuthUiState(
            isInitializing = false,
            feedback = AuthFeedback("账户及云端数据已删除。", AuthFeedbackKind.SUCCESS),
        )
    }

    private fun validateNewPassword(snapshot: AuthUiState) = AuthFieldErrors(
        password = if (snapshot.password.length < MIN_PASSWORD_LENGTH) "密码至少需要八位。" else null,
        confirmPassword = when {
            snapshot.confirmPassword.isBlank() -> "请再次输入密码。"
            snapshot.password != snapshot.confirmPassword -> "两次输入的密码不一致。"
            else -> null
        },
    )

    private fun showCodeStep(purpose: AuthCodePurpose, message: String) {
        mutableState.update {
            it.copy(
                step = AuthStep.CODE,
                codePurpose = purpose,
                password = "",
                confirmPassword = "",
                token = "",
                fieldErrors = AuthFieldErrors(),
                feedback = null,
                codeDeliveryNotice = message,
                resendSecondsRemaining = RESEND_DELAY_SECONDS,
            )
        }
        startResendCountdown()
    }

    private fun completeAuthentication(session: AuthSession, feedback: AuthFeedback? = null) {
        mutableState.update {
            it.copy(
                step = AuthStep.AUTHENTICATED,
                codePurpose = null,
                password = "",
                confirmPassword = "",
                token = "",
                session = session,
                fieldErrors = AuthFieldErrors(),
                feedback = feedback,
                codeDeliveryNotice = null,
                resendSecondsRemaining = 0,
            )
        }
        resendCountdownJob?.cancel()
    }

    private fun AuthCodePurpose.toRepositoryPurpose() = when (this) {
        AuthCodePurpose.PASSWORDLESS_LOGIN -> EmailOtpPurpose.PASSWORDLESS_LOGIN
        AuthCodePurpose.PASSWORDLESS_REGISTRATION -> EmailOtpPurpose.PASSWORDLESS_REGISTRATION
        AuthCodePurpose.SIGNUP_CONFIRMATION -> EmailOtpPurpose.SIGNUP_CONFIRMATION
        AuthCodePurpose.PASSWORD_RECOVERY,
        AuthCodePurpose.ACCOUNT_PASSWORD_UPDATE -> EmailOtpPurpose.PASSWORD_RECOVERY
    }

    private fun resendFeedback(purpose: AuthCodePurpose) = when (purpose) {
        AuthCodePurpose.PASSWORD_RECOVERY,
        AuthCodePurpose.ACCOUNT_PASSWORD_UPDATE -> "若账号存在，恢复码已重新发送。"
        AuthCodePurpose.SIGNUP_CONFIRMATION -> "确认码已重新发送。"
        AuthCodePurpose.PASSWORDLESS_LOGIN,
        AuthCodePurpose.PASSWORDLESS_REGISTRATION -> "验证码已重新发送。"
    }

    private fun launchRequest(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, feedback = null) }
            runCatching { block() }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(feedback = AuthFeedback(error.toUserMessage(), AuthFeedbackKind.ERROR))
                    }
                }
            mutableState.update { it.copy(isLoading = false) }
        }
    }

    private fun startResendCountdown() {
        resendCountdownJob?.cancel()
        resendCountdownJob = viewModelScope.launch {
            while (state.value.resendSecondsRemaining > 0 && state.value.step == AuthStep.CODE) {
                delay(1_000L)
                mutableState.update {
                    it.copy(resendSecondsRemaining = (it.resendSecondsRemaining - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun Throwable.toUserMessage(): String {
        val raw = message.orEmpty()
        val normalized = raw.lowercase()
        return when {
            raw.startsWith("尚未配置 Supabase") -> raw
            "invalid login credentials" in normalized || "invalid_credentials" in normalized -> "邮箱或密码错误。"
            "weak_password" in normalized || "weak password" in normalized -> "密码不符合安全要求，请使用至少八位密码。"
            "otp" in normalized && ("expired" in normalized || "invalid" in normalized) -> "验证码错误或已过期。"
            "token" in normalized && ("expired" in normalized || "invalid" in normalized) -> "验证码错误或已过期。"
            "rate limit" in normalized || "too many" in normalized || "over_email_send_rate_limit" in normalized ->
                "请求过于频繁，请稍后再试。"
            "already registered" in normalized || "user_already_exists" in normalized ->
                "该邮箱无法完成注册，请改用登录或找回密码。"
            else -> "请求失败，请稍后重试。"
        }
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
        const val CODE_LENGTH = 6
        const val RESEND_DELAY_SECONDS = 60
        val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
