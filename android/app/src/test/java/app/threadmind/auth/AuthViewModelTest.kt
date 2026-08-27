package app.threadmind.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `registration requires privacy acceptance and creates a user`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        runCurrent()
        viewModel.setEmail("person@example.com")

        viewModel.requestRegistrationCode()
        assertEquals("注册前请先同意隐私与数据处理说明。", viewModel.state.value.message)
        assertTrue(repository.requests.isEmpty())

        viewModel.setPrivacyAccepted(true)
        viewModel.requestRegistrationCode()
        runCurrent()

        assertEquals(listOf("person@example.com" to true), repository.requests)
        assertEquals(AuthStep.OTP, viewModel.state.value.step)
    }

    @Test fun `login does not create a user and six digit code creates session`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        runCurrent()
        viewModel.setEmail("person@example.com")
        viewModel.requestLoginCode()
        runCurrent()
        assertEquals(listOf("person@example.com" to false), repository.requests)

        viewModel.setToken("12a34567")
        assertEquals("123456", viewModel.state.value.token)
        viewModel.verifyCode()
        runCurrent()

        assertEquals(AuthStep.AUTHENTICATED, viewModel.state.value.step)
        assertEquals("user-1", viewModel.state.value.session?.userId)
        assertFalse(viewModel.state.value.isLoading)
    }
}

private class FakeAuthRepository : AuthRepository {
    val requests = mutableListOf<Pair<String, Boolean>>()
    private var session: AuthSession? = null

    override suspend fun restoreSession(): AuthSession? = session
    override fun currentAccessToken(): String? = session?.let { "access-token" }

    override suspend fun requestEmailOtp(email: String, createUser: Boolean) {
        requests += email to createUser
    }

    override suspend fun verifyEmailOtp(email: String, token: String): AuthSession =
        AuthSession("user-1", email).also { session = it }

    override suspend fun signOut() {
        session = null
    }
}
