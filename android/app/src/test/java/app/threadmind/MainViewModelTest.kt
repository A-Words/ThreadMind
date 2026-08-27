package app.threadmind

import app.threadmind.domain.ConfirmedActionSnapshot
import app.threadmind.network.MemoryListResponse
import app.threadmind.network.ThreadMindApi
import app.threadmind.provider.ProviderExecutor
import app.threadmind.provider.ProviderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `backend check exposes authenticated API success`() = runTest(dispatcher) {
        val viewModel = MainViewModel(FakeProviderExecutor(), FakeThreadMindApi())

        viewModel.checkBackend()
        runCurrent()

        assertEquals(BackendStatus.CONNECTED, viewModel.state.value.backendStatus)
        assertEquals("服务端已连接（0 条记忆）", viewModel.state.value.backendMessage)
    }
}

private class FakeThreadMindApi : ThreadMindApi {
    override suspend fun listMemories() = MemoryListResponse(emptyList())
}

private class FakeProviderExecutor : ProviderExecutor {
    override suspend fun execute(snapshot: ConfirmedActionSnapshot) = ProviderResult.Succeeded("record-1")
}
