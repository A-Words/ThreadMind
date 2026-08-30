package app.threadmind

import android.net.Uri
import app.threadmind.domain.ActionCard
import app.threadmind.domain.ActionCardPolicy
import app.threadmind.domain.ActionStatus
import app.threadmind.domain.ActionType
import app.threadmind.domain.ConfirmedActionSnapshot
import app.threadmind.domain.EvidenceRef
import app.threadmind.network.MemoryListResponse
import app.threadmind.network.MemoryRecordResponse
import app.threadmind.network.MemoryRevisionRequest
import app.threadmind.network.ActionCardListResponse
import app.threadmind.network.ActionCardEditRequest
import app.threadmind.network.ActionCardResponse
import app.threadmind.network.ActionReceiptRequest
import app.threadmind.network.CardVersionRequest
import app.threadmind.network.SubmissionResponse
import app.threadmind.network.SubmissionProgress
import app.threadmind.network.SubmissionWorkflowRepository
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
import okhttp3.MultipartBody
import okhttp3.RequestBody

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `backend check exposes authenticated API success`() = runTest(dispatcher) {
        val viewModel = MainViewModel(FakeProviderExecutor(), FakeThreadMindApi(), FakeSubmissionWorkflowRepository())

        viewModel.checkBackend()
        runCurrent()

        assertEquals(BackendStatus.CONNECTED, viewModel.state.value.backendStatus)
        assertEquals("服务端已连接（1 条记忆）", viewModel.state.value.backendMessage)
        assertEquals("memory-1", viewModel.state.value.memories.single().id)
    }

    @Test fun `memory correction replaces active item with revised version`() = runTest(dispatcher) {
        val api = FakeThreadMindApi()
        val viewModel = MainViewModel(FakeProviderExecutor(), api, FakeSubmissionWorkflowRepository())
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
        val viewModel = MainViewModel(FakeProviderExecutor(), FakeThreadMindApi(), FakeSubmissionWorkflowRepository())
        viewModel.checkBackend()
        runCurrent()

        viewModel.deleteMemory("memory-1")
        runCurrent()

        assertEquals(emptyList<MemoryRecordResponse>(), viewModel.state.value.memories)
        assertEquals("服务端已连接（0 条记忆）", viewModel.state.value.backendMessage)
        assertEquals("记忆已删除", viewModel.state.value.memoryMessage)
    }

    @Test fun `confirmation comes from server and provider result uploads a receipt`() = runTest(dispatcher) {
        val submissions = FakeSubmissionWorkflowRepository()
        val viewModel = MainViewModel(FakeProviderExecutor(), FakeThreadMindApi(), submissions)
        viewModel.showCards(listOf(actionCard()))

        viewModel.confirm("card-1")
        runCurrent()
        assertEquals(ActionStatus.CONFIRMED, viewModel.state.value.cards.single().status)

        viewModel.execute("card-1")

        assertEquals(ActionStatus.SUCCEEDED, viewModel.state.value.cards.single().status)
        assertEquals("succeeded", submissions.receipts.single().status)
        assertEquals("record-1", submissions.receipts.single().targetRecordId)
    }
}

private class FakeSubmissionWorkflowRepository : SubmissionWorkflowRepository {
    val receipts = mutableListOf<ActionReceiptRequest>()

    override suspend fun submit(uri: Uri, submissionId: String, source: String, supplementalText: String) = error("unused")
    override suspend fun refresh(submissionId: String): SubmissionProgress = error("unused")
    override suspend fun edit(cardId: String, expectedVersion: Int, fields: Map<String, String>, targetAccountId: String, resolvedValidationIssues: List<String>) =
        ActionCardPolicy.edit(actionCard().copy(version = expectedVersion), fields, resolvedValidationIssues.toSet()).copy(targetAccountId = targetAccountId)
    override suspend fun confirm(cardId: String, expectedVersion: Int) = ActionCardPolicy.confirm(actionCard().copy(version = expectedVersion))
    override suspend fun cancel(cardId: String) = Unit
    override suspend fun reportExecution(cardId: String, request: ActionReceiptRequest) { receipts += request }
}

private fun actionCard() = ActionCard(
    id = "card-1",
    submissionId = "submission-1",
    type = ActionType.CREATE_CONTACT,
    version = 1,
    fields = mapOf("displayName" to "Chen", "contactMethod" to "chen@example.com", "targetContactAccountId" to "local"),
    evidence = listOf(EvidenceRef("submission-1", "m1", "chen@example.com", 0.99)),
    fieldConfidence = mapOf("displayName" to 0.9, "contactMethod" to 0.99, "targetContactAccountId" to 1.0),
    validationIssues = emptyList(),
    targetAccountId = "local",
    status = ActionStatus.READY,
    blockers = emptyList(),
)

private class FakeThreadMindApi : ThreadMindApi {
    private var memory: MemoryRecordResponse? = memoryRecord()

    override suspend fun listMemories() = MemoryListResponse(listOfNotNull(memory))

    override suspend fun createSubmission(image: MultipartBody.Part, submissionId: RequestBody, source: RequestBody, supplementalText: RequestBody?): SubmissionResponse = error("unused")
    override suspend fun getSubmission(id: String): SubmissionResponse = error("unused")
    override suspend fun listActionCards(id: String): ActionCardListResponse = error("unused")
    override suspend fun confirmActionCard(id: String, request: CardVersionRequest): ActionCardResponse = error("unused")
    override suspend fun editActionCard(id: String, request: ActionCardEditRequest): ActionCardResponse = error("unused")
    override suspend fun cancelActionCard(id: String): Response<Unit> = error("unused")
    override suspend fun createActionReceipt(id: String, request: ActionReceiptRequest) = error("unused")

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
