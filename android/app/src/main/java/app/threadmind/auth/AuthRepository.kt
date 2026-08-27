package app.threadmind.auth

data class AuthSession(
    val userId: String,
    val email: String?,
)

fun interface AccessTokenProvider {
    fun currentAccessToken(): String?
}

interface AuthRepository : AccessTokenProvider {
    suspend fun restoreSession(): AuthSession?
    suspend fun requestEmailOtp(email: String, createUser: Boolean)
    suspend fun verifyEmailOtp(email: String, token: String): AuthSession
    suspend fun signOut()
}

class UnavailableAuthRepository(
    private val reason: String,
) : AuthRepository {
    override suspend fun restoreSession(): AuthSession? = null
    override fun currentAccessToken(): String? = null
    override suspend fun requestEmailOtp(email: String, createUser: Boolean): Nothing = error(reason)
    override suspend fun verifyEmailOtp(email: String, token: String): Nothing = error(reason)
    override suspend fun signOut(): Nothing = error(reason)
}
