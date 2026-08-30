package app.threadmind.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
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

    override suspend fun signInWithPassword(email: String, password: String): AuthSession {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        return requireNotNull(currentSession()) { "Supabase did not create a session" }
    }

    override suspend fun signUpWithPassword(email: String, password: String): AuthSession? {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        return currentSession()
    }

    override suspend fun requestEmailOtp(email: String, createUser: Boolean) {
        client.auth.signInWith(OTP) {
            this.email = email
            this.createUser = createUser
        }
    }

    override suspend fun requestPasswordRecovery(email: String) {
        client.auth.resetPasswordForEmail(email)
    }

    override suspend fun resendEmailOtp(email: String, purpose: EmailOtpPurpose) {
        when (purpose) {
            EmailOtpPurpose.PASSWORDLESS_LOGIN -> requestEmailOtp(email, createUser = false)
            EmailOtpPurpose.PASSWORDLESS_REGISTRATION -> requestEmailOtp(email, createUser = true)
            EmailOtpPurpose.SIGNUP_CONFIRMATION -> client.auth.resendEmail(OtpType.Email.SIGNUP, email)
            EmailOtpPurpose.PASSWORD_RECOVERY -> requestPasswordRecovery(email)
        }
    }

    override suspend fun verifyEmailOtp(
        email: String,
        token: String,
        purpose: EmailOtpPurpose,
    ): AuthSession {
        val type = when (purpose) {
            EmailOtpPurpose.PASSWORDLESS_LOGIN,
            EmailOtpPurpose.PASSWORDLESS_REGISTRATION -> OtpType.Email.EMAIL
            EmailOtpPurpose.SIGNUP_CONFIRMATION -> OtpType.Email.SIGNUP
            EmailOtpPurpose.PASSWORD_RECOVERY -> OtpType.Email.RECOVERY
        }
        client.auth.verifyEmailOtp(type = type, email = email, token = token)
        return requireNotNull(currentSession()) { "Supabase did not create a session" }
    }

    override suspend fun updatePassword(password: String): AuthSession {
        client.auth.updateUser { this.password = password }
        return requireNotNull(currentSession()) { "Supabase session is unavailable after password update" }
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }
}
