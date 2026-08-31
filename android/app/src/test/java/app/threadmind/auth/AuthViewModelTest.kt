package app.threadmind.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `password login is default and initialization is separate from requests`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        assertTrue(viewModel.state.value.isInitializing)
        assertFalse(viewModel.state.value.isLoading)

        runCurrent()
        assertFalse(viewModel.state.value.isInitializing)
        assertEquals(AuthIntent.LOGIN, viewModel.state.value.intent)
        assertEquals(AuthMethod.PASSWORD, viewModel.state.value.method)

        viewModel.setEmail("person@example.com")
        viewModel.setPassword("existing-password")
        viewModel.submitCredentials()
        runCurrent()

        assertEquals(listOf("person@example.com" to "existing-password"), repository.passwordSignIns)
        assertEquals(AuthStep.AUTHENTICATED, viewModel.state.value.step)
        assertEquals("user-1", viewModel.state.value.session?.userId)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test fun `validation assigns errors to fields and server failures use form feedback`() = runTest(dispatcher) {
        val repository = FakeAuthRepository().apply {
            signInError = IllegalStateException("Invalid login credentials")
        }
        val viewModel = AuthViewModel(repository)
        runCurrent()

        viewModel.submitCredentials()
        assertEquals("请输入有效的邮箱地址。", viewModel.state.value.fieldErrors.email)
        assertEquals("请输入密码。", viewModel.state.value.fieldErrors.password)
        assertNull(viewModel.state.value.feedback)

        viewModel.setEmail("person@example.com")
        viewModel.setPassword("wrong-password")
        viewModel.submitCredentials()
        runCurrent()

        assertEquals("邮箱或密码错误。", viewModel.state.value.feedback?.message)
        assertEquals(AuthFeedbackKind.ERROR, viewModel.state.value.feedback?.kind)
        assertEquals(AuthStep.CREDENTIALS, viewModel.state.value.step)
    }

    @Test fun `switching intent or method preserves email and clears sensitive fields`() = runTest(dispatcher) {
        val viewModel = AuthViewModel(FakeAuthRepository())
        runCurrent()
        viewModel.setEmail("person@example.com")
        viewModel.setPassword("secret-password")
        viewModel.setConfirmPassword("secret-password")

        viewModel.setIntent(AuthIntent.REGISTER)
        assertEquals("person@example.com", viewModel.state.value.email)
        assertEquals("", viewModel.state.value.password)
        assertEquals("", viewModel.state.value.confirmPassword)
        assertEquals(AuthMethod.PASSWORD, viewModel.state.value.method)

        viewModel.setPassword("another-secret")
        viewModel.setMethod(AuthMethod.OTP)
        assertEquals("person@example.com", viewModel.state.value.email)
        assertEquals("", viewModel.state.value.password)
    }

    @Test fun `password registration enforces privacy length and confirmation then verifies signup`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        runCurrent()
        viewModel.setIntent(AuthIntent.REGISTER)
        viewModel.setEmail("person@example.com")
        viewModel.setPassword("short")
        viewModel.setConfirmPassword("different")

        viewModel.submitCredentials()
        assertEquals("密码至少需要八位。", viewModel.state.value.fieldErrors.password)
        assertEquals("两次输入的密码不一致。", viewModel.state.value.fieldErrors.confirmPassword)
        assertEquals("注册前请先同意隐私与数据处理说明。", viewModel.state.value.fieldErrors.privacy)
        assertTrue(repository.passwordSignUps.isEmpty())

        viewModel.setPassword("new-password")
        viewModel.setConfirmPassword("new-password")
        viewModel.setPrivacyAccepted(true)
        viewModel.submitCredentials()
        runCurrent()

        assertEquals(AuthStep.CODE, viewModel.state.value.step)
        assertEquals(AuthCodePurpose.SIGNUP_CONFIRMATION, viewModel.state.value.codePurpose)
        assertEquals(60, viewModel.state.value.resendSecondsRemaining)

        viewModel.setToken("12a34567")
        assertEquals("123456", viewModel.state.value.token)
        viewModel.verifyCode()
        runCurrent()

        assertEquals(
            Verification("person@example.com", "123456", EmailOtpPurpose.SIGNUP_CONFIRMATION),
            repository.verifications.single(),
        )
        assertEquals(AuthStep.AUTHENTICATED, viewModel.state.value.step)
    }

    @Test fun `otp login and registration retain distinct non enumerating purposes`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        runCurrent()
        viewModel.setMethod(AuthMethod.OTP)
        viewModel.setEmail("person@example.com")
        viewModel.submitCredentials()
        runCurrent()
        assertEquals("person@example.com" to false, repository.otpRequests.single())
        viewModel.setToken("123456")
        viewModel.verifyCode()
        runCurrent()
        assertEquals(EmailOtpPurpose.PASSWORDLESS_LOGIN, repository.verifications.single().purpose)

        viewModel.signOut()
        runCurrent()
        viewModel.setIntent(AuthIntent.REGISTER)
        viewModel.setMethod(AuthMethod.OTP)
        viewModel.setEmail("person@example.com")
        viewModel.setPrivacyAccepted(true)
        viewModel.submitCredentials()
        runCurrent()
        assertEquals("person@example.com" to true, repository.otpRequests.last())
        assertEquals(AuthCodePurpose.PASSWORDLESS_REGISTRATION, viewModel.state.value.codePurpose)
    }

    @Test fun `resend waits sixty seconds and preserves every code purpose`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        runCurrent()
        viewModel.setIntent(AuthIntent.REGISTER)
        viewModel.setMethod(AuthMethod.OTP)
        viewModel.setEmail("person@example.com")
        viewModel.setPrivacyAccepted(true)
        viewModel.submitCredentials()
        runCurrent()

        viewModel.resendCode()
        runCurrent()
        assertTrue(repository.resends.isEmpty())
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(0, viewModel.state.value.resendSecondsRemaining)
        viewModel.resendCode()
        runCurrent()
        assertEquals(
            "person@example.com" to EmailOtpPurpose.PASSWORDLESS_REGISTRATION,
            repository.resends.single(),
        )
        assertEquals("验证码已重新发送。", viewModel.state.value.codeDeliveryNotice)
        assertEquals(60, viewModel.state.value.resendSecondsRemaining)
    }

    @Test fun `code input filters pasted text and resend has independent loading state`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        runCurrent()
        viewModel.setMethod(AuthMethod.OTP)
        viewModel.setEmail("person@example.com")
        viewModel.submitCredentials()
        runCurrent()

        assertEquals("验证码已发送，请检查邮箱。", viewModel.state.value.codeDeliveryNotice)
        viewModel.setToken(" 12a34-5678 ")
        assertEquals("123456", viewModel.state.value.token)

        advanceTimeBy(60_000L)
        runCurrent()
        repository.resendGate = CompletableDeferred()
        viewModel.resendCode()
        runCurrent()
        assertTrue(viewModel.state.value.isResendingCode)
        assertFalse(viewModel.state.value.isLoading)

        repository.resendGate?.complete(Unit)
        runCurrent()
        assertFalse(viewModel.state.value.isResendingCode)
        assertEquals("", viewModel.state.value.token)
        assertEquals("验证码已重新发送。", viewModel.state.value.codeDeliveryNotice)
    }

    @Test fun `password recovery verifies code updates password and reports success`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        runCurrent()
        viewModel.setEmail("person@example.com")
        viewModel.startPasswordRecovery()
        runCurrent()

        assertEquals(listOf("person@example.com"), repository.recoveryRequests)
        assertEquals("若账号存在，恢复码已发送，请检查邮箱。", viewModel.state.value.codeDeliveryNotice)
        viewModel.setToken("123456")
        viewModel.verifyCode()
        runCurrent()
        assertEquals(EmailOtpPurpose.PASSWORD_RECOVERY, repository.verifications.single().purpose)
        assertEquals(AuthStep.NEW_PASSWORD, viewModel.state.value.step)

        viewModel.setPassword("new-password")
        viewModel.setConfirmPassword("different-password")
        viewModel.updatePassword()
        assertEquals("两次输入的密码不一致。", viewModel.state.value.fieldErrors.confirmPassword)

        viewModel.setConfirmPassword("new-password")
        viewModel.updatePassword()
        runCurrent()
        assertEquals(listOf("new-password"), repository.updatedPasswords)
        assertEquals(AuthStep.AUTHENTICATED, viewModel.state.value.step)
        assertEquals(AuthFeedbackKind.SUCCESS, viewModel.state.value.feedback?.kind)
    }

    @Test fun `session restore account password flow cancellation and sign out remain safe`() = runTest(dispatcher) {
        val repository = FakeAuthRepository(initialSession = AuthSession("otp-user", "otp@example.com"))
        val viewModel = AuthViewModel(repository)
        runCurrent()
        assertEquals(AuthStep.AUTHENTICATED, viewModel.state.value.step)

        viewModel.beginPasswordUpdate()
        runCurrent()
        assertEquals(AuthCodePurpose.ACCOUNT_PASSWORD_UPDATE, viewModel.state.value.codePurpose)
        viewModel.cancelFlow()
        assertEquals(AuthStep.AUTHENTICATED, viewModel.state.value.step)

        viewModel.signOut()
        runCurrent()
        assertTrue(repository.didSignOut)
        assertEquals(AuthStep.CREDENTIALS, viewModel.state.value.step)
        assertNull(viewModel.state.value.session)
        assertFalse(viewModel.state.value.isInitializing)
    }

    @Test fun `cancelling recovery after verification revokes temporary session`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        runCurrent()
        viewModel.setEmail("person@example.com")
        viewModel.startPasswordRecovery()
        runCurrent()
        viewModel.setToken("123456")
        viewModel.verifyCode()
        runCurrent()
        viewModel.cancelFlow()
        runCurrent()

        assertTrue(repository.didSignOut)
        assertEquals(AuthStep.CREDENTIALS, viewModel.state.value.step)
        assertNull(viewModel.state.value.session)
    }
}

private data class Verification(val email: String, val token: String, val purpose: EmailOtpPurpose)

private class FakeAuthRepository(initialSession: AuthSession? = null) : AuthRepository {
    val passwordSignIns = mutableListOf<Pair<String, String>>()
    val passwordSignUps = mutableListOf<Pair<String, String>>()
    val otpRequests = mutableListOf<Pair<String, Boolean>>()
    val recoveryRequests = mutableListOf<String>()
    val resends = mutableListOf<Pair<String, EmailOtpPurpose>>()
    val verifications = mutableListOf<Verification>()
    val updatedPasswords = mutableListOf<String>()
    var signInError: Throwable? = null
    var signUpSession: AuthSession? = null
    var resendGate: CompletableDeferred<Unit>? = null
    var didSignOut = false
    private var session: AuthSession? = initialSession

    override suspend fun restoreSession(): AuthSession? = session
    override fun currentUserId(): String? = session?.userId
    override fun currentAccessToken(): String? = session?.let { "access-token" }

    override suspend fun signInWithPassword(email: String, password: String): AuthSession {
        passwordSignIns += email to password
        signInError?.let { throw it }
        return AuthSession("user-1", email).also { session = it }
    }

    override suspend fun signUpWithPassword(email: String, password: String): AuthSession? {
        passwordSignUps += email to password
        return signUpSession?.also { session = it }
    }

    override suspend fun requestEmailOtp(email: String, createUser: Boolean) {
        otpRequests += email to createUser
    }

    override suspend fun requestPasswordRecovery(email: String) {
        recoveryRequests += email
    }

    override suspend fun resendEmailOtp(email: String, purpose: EmailOtpPurpose) {
        resends += email to purpose
        resendGate?.await()
    }

    override suspend fun verifyEmailOtp(
        email: String,
        token: String,
        purpose: EmailOtpPurpose,
    ): AuthSession {
        verifications += Verification(email, token, purpose)
        return AuthSession("user-1", email).also { session = it }
    }

    override suspend fun updatePassword(password: String): AuthSession {
        updatedPasswords += password
        return requireNotNull(session)
    }

    override suspend fun signOut() {
        didSignOut = true
        session = null
    }
}
