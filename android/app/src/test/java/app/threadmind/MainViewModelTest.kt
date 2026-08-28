package app.threadmind

import app.threadmind.domain.ConfirmedActionSnapshot
import app.threadmind.network.MemoryListResponse
import app.threadmind.network.MemoryRecordResponse
import app.threadmind.network.MemoryRevisionRequest
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
import retrofit2.Response

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
        assertEquals("服务端已连接（1 条记忆）", viewModel.state.value.backendMessage)
        assertEquals("memory-1", viewModel.state.value.memories.single().id)
    }

    @Test fun `memory correction replaces active item with revised version`() = runTest(dispatcher) {
        val api = FakeThreadMindApi()
        val viewModel = MainViewModel(FakeProviderExecutor(), api)
        viewModel.checkBackend()
        runCurrent()

        viewModel.reviseMemory("memory-1", "已由用户确认")
        runCurrent()

        val revised = viewModel.state.value.memories.single()
        assertEquals("memory-2", revised.id)
        assertEquals("已由用户确认", revised.assertion)
        assertEquals(2, revised.version)
        assertEquals("fact", revised.epistemicStatus)
        assertEquals("memory-1", revised.supersedesId)
        assertEquals("已保存为第 2 版，并保留历史版本", viewModel.state.value.memoryMessage)
    }

    @Test fun `memory deletion removes item from active center`() = runTest(dispatcher) {
        val viewModel = MainViewModel(FakeProviderExecutor(), FakeThreadMindApi())
        viewModel.checkBackend()
        runCurrent()

        viewModel.deleteMemory("memory-1")
        runCurrent()

        assertEquals(emptyList<MemoryRecordResponse>(), viewModel.state.value.memories)
        assertEquals("服务端已连接（0 条记忆）", viewModel.state.value.backendMessage)
        assertEquals("记忆已删除", viewModel.state.value.memoryMessage)
    }
}

private class FakeThreadMindApi : ThreadMindApi {
    private var memory: MemoryRecordResponse? = memoryRecord()

    override suspend fun listMemories() = MemoryListResponse(listOfNotNull(memory))

    override suspend fun reviseMemory(id: String, request: MemoryRevisionRequest): MemoryRecordResponse {
        check(memory?.id == id)
        return memoryRecord(
            id = "memory-2",
            assertion = request.assertion,
            version = 2,
            supersedesId = id,
        ).also { memory = it }
    }

    override suspend fun deleteMemory(id: String): Response<Unit> {
        check(memory?.id == id)
        memory = null
        return Response.success(Unit)
    }
}

private fun memoryRecord(
    id: String = "memory-1",
    assertion: String = "用户偏好安静的餐厅",
    version: Int = 1,
    supersedesId: String? = null,
) = MemoryRecordResponse(
    id = id,
    subjectRefs = listOf("self"),
    type = "preference",
    assertion = assertion,
    epistemicStatus = "fact",
    confidence = 1.0,
    sensitivity = "normal",
    sourceRefs = listOf("conversation:1"),
    createdAt = "2026-08-28T00:00:00Z",
    updatedAt = "2026-08-28T00:00:00Z",
    version = version,
    supersedesId = supersedesId,
    status = "active",
)

private class FakeProviderExecutor : ProviderExecutor {
    override suspend fun execute(snapshot: ConfirmedActionSnapshot) = ProviderResult.Succeeded("record-1")
}
