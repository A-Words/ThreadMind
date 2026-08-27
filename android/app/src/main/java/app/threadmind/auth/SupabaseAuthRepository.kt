package app.threadmind.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP

class SupabaseAuthRepository(
    private val client: SupabaseClient,
) : AuthRepository {
    override suspend fun restoreSession(): AuthSession? {
        client.auth.awaitInitialization()
        return currentSession()
    }

    private fun currentSession(): AuthSession? = client.auth.currentSessionOrNull()?.let { session ->
        AuthSession(userId = session.user?.id ?: return null, email = session.user?.email)
    }

    override fun currentAccessToken(): String? = client.auth.currentSessionOrNull()?.accessToken

    override suspend fun requestEmailOtp(email: String, createUser: Boolean) {
        client.auth.signInWith(OTP) {
            this.email = email
            this.createUser = createUser
        }
    }

    override suspend fun verifyEmailOtp(email: String, token: String): AuthSession {
        client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email, token = token)
        return requireNotNull(currentSession()) { "Supabase did not create a session" }
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }
}
