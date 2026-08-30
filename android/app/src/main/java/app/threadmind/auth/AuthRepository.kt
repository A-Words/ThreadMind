package app.threadmind.auth

data class AuthSession(
    val userId: String,
    val email: String?,
)

fun interface AccessTokenProvider {
    fun currentAccessToken(): String?
}

enum class EmailOtpPurpose {
    PASSWORDLESS_LOGIN,
    PASSWORDLESS_REGISTRATION,
    SIGNUP_CONFIRMATION,
    PASSWORD_RECOVERY,
}

interface AuthRepository : AccessTokenProvider {
    suspend fun restoreSession(): AuthSession?
    suspend fun signInWithPassword(email: String, password: String): AuthSession
    suspend fun signUpWithPassword(email: String, password: String): AuthSession?
    suspend fun requestEmailOtp(email: String, createUser: Boolean)
    suspend fun requestPasswordRecovery(email: String)
    suspend fun resendEmailOtp(email: String, purpose: EmailOtpPurpose)
    suspend fun verifyEmailOtp(email: String, token: String, purpose: EmailOtpPurpose): AuthSession
    suspend fun updatePassword(password: String): AuthSession
    suspend fun signOut()
}

class UnavailableAuthRepository(
    private val reason: String,
) : AuthRepository {
    override suspend fun restoreSession(): AuthSession? = null
    override fun currentAccessToken(): String? = null
    override suspend fun signInWithPassword(email: String, password: String): Nothing = error(reason)
    override suspend fun signUpWithPassword(email: String, password: String): Nothing = error(reason)
    override suspend fun requestEmailOtp(email: String, createUser: Boolean): Nothing = error(reason)
    override suspend fun requestPasswordRecovery(email: String): Nothing = error(reason)
    override suspend fun resendEmailOtp(email: String, purpose: EmailOtpPurpose): Nothing = error(reason)
    override suspend fun verifyEmailOtp(
        email: String,
        token: String,
        purpose: EmailOtpPurpose,
    ): Nothing = error(reason)
    override suspend fun updatePassword(password: String): Nothing = error(reason)
    override suspend fun signOut(): Nothing = error(reason)
}
