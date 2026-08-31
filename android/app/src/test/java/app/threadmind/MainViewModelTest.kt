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
import app.threadmind.network.InsightBundleResponse
import app.threadmind.network.InsightItemResponse
import app.threadmind.network.InsightListResponse
import app.threadmind.network.EvidenceRefResponse
import app.threadmind.network.ExtractionResponse
import app.threadmind.network.ExtractionMessageResponse
import app.threadmind.network.ActionCardListResponse
import app.threadmind.network.ActionCardEditRequest
import app.threadmind.network.ActionCardResponse
import app.threadmind.network.ActionReceiptRequest
import app.threadmind.network.AccountExportPayload
import app.threadmind.network.ClearMemoriesResponse
import app.threadmind.network.CardVersionRequest
import app.threadmind.network.SubmissionResponse
import app.threadmind.network.SubmissionProgress
import app.threadmind.network.SubmissionWorkflowRepository
import app.threadmind.network.ThreadMindApi
import app.threadmind.provider.ProviderExecutor
import app.threadmind.provider.ProviderResult
import app.threadmind.provider.ContactCandidate
import app.threadmind.provider.ContactFieldChange
import app.threadmind.provider.MeetingConflict
import app.threadmind.provider.ProviderPreflightResult
import app.threadmind.provider.ProviderTarget
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
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

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
        assertEquals(ActionStatus.READY, viewModel.state.value.cards.single().status)
        assertEquals("确认前请先检查设备中的重复项、冲突和目标账户", viewModel.state.value.message)

        viewModel.preflightProvider("card-1")
        runCurrent()
        viewModel.confirm("card-1")
        runCurrent()
        assertEquals(ActionStatus.CONFIRMED, viewModel.state.value.cards.single().status)

        viewModel.execute("card-1")

        assertEquals(ActionStatus.SUCCEEDED, viewModel.state.value.cards.single().status)
        assertEquals("succeeded", submissions.receipts.single().status)
        assertEquals("record-1", submissions.receipts.single().targetRecordId)
        assertEquals("insight-1", viewModel.state.value.insights.single().id)
    }

    @Test fun `backend check restores latest review and pending receipt`() = runTest(dispatcher) {
        val pendingReceipt = ActionReceiptRequest("receipt-1", "succeeded", targetRecordId = "record-1")
        val restoredCard = ActionCardPolicy.confirm(actionCard())
        val submissions = FakeSubmissionWorkflowRepository(
            restored = SubmissionProgress(
                submissionId = "submission-1",
                status = "ready",
                cards = listOf(restoredCard),
                pendingReceipts = mapOf("card-1" to pendingReceipt),
                providerReviewedVersions = setOf("card-1:1"),
                analysis = ExtractionResponse(
                    id = "extraction-1",
                    submissionId = "submission-1",
                    messages = listOf(ExtractionMessageResponse("m1", 0, "下周见", "对方", 0.72)),
                    warnings = listOf("说话人置信度较低"),
                    createdAt = "2026-09-01T00:00:00Z",
                ),
            ),
        )
        val provider = FakeProviderExecutor()
        val viewModel = MainViewModel(provider, FakeThreadMindApi(), submissions)

        viewModel.checkBackend()
        runCurrent()

        assertEquals("submission-1", viewModel.state.value.submissionId)
        assertEquals(ActionStatus.CONFIRMED, viewModel.state.value.cards.single().status)
        assertEquals(pendingReceipt, viewModel.state.value.pendingReceipts["card-1"])
        assertEquals("下周见", viewModel.state.value.analysis?.messages?.single()?.text)
        assertEquals("分析完成：1 张待审核卡片，云端原图已删除", viewModel.state.value.submissionMessage)

        viewModel.execute("card-1")

        assertEquals(0, provider.executionCount)
        assertEquals("执行回执尚未同步，请勿再次写入系统", viewModel.state.value.message)
    }

    @Test fun `backend check clears a previous account review when none is stored`() = runTest(dispatcher) {
        val viewModel = MainViewModel(FakeProviderExecutor(), FakeThreadMindApi(), FakeSubmissionWorkflowRepository())
        viewModel.showCards(listOf(actionCard()))

        viewModel.checkBackend()
        runCurrent()

        assertEquals(emptyList<ActionCard>(), viewModel.state.value.cards)
        assertEquals(null, viewModel.state.value.submissionId)
        assertEquals(emptyMap<String, ActionReceiptRequest>(), viewModel.state.value.pendingReceipts)
    }

    @Test fun `memory filters are sent to the server`() = runTest(dispatcher) {
        val api = FakeThreadMindApi()
        val viewModel = MainViewModel(FakeProviderExecutor(), api, FakeSubmissionWorkflowRepository())
        viewModel.setMemorySearch("  chen  ")
        viewModel.setMemorySubjectRef("contact-1")
        viewModel.setMemoryType("profile")
        viewModel.setMemoryTimeFilter(MemoryTimeFilter.LAST_30_DAYS)

        viewModel.refreshMemories()
        runCurrent()

        assertEquals("chen", api.lastMemorySearch)
        assertEquals("contact-1", api.lastMemorySubjectRef)
        assertEquals("profile", api.lastMemoryType)
        assertEquals(true, api.lastMemoryCreatedFrom?.isNotBlank())
        assertEquals("找到 1 条活动记忆", viewModel.state.value.memoryMessage)
    }

    @Test fun `data controls delete submission clear memory export and delete account`() = runTest(dispatcher) {
        val submissions = FakeSubmissionWorkflowRepository(
            restored = SubmissionProgress("submission-1", "ready", listOf(actionCard())),
        )
        val viewModel = MainViewModel(FakeProviderExecutor(), FakeThreadMindApi(), submissions)
        viewModel.checkBackend()
        runCurrent()

        viewModel.deleteCurrentSubmission()
        runCurrent()
        assertEquals(listOf("submission-1"), submissions.deletedSubmissionIds)
        assertEquals(null, viewModel.state.value.submissionId)
        assertEquals("本次提交及其派生数据已删除", viewModel.state.value.dataMessage)

        viewModel.clearAllMemories()
        runCurrent()
        assertEquals(emptyList<MemoryRecordResponse>(), viewModel.state.value.memories)
        assertEquals("长期记忆已全部清除", viewModel.state.value.dataMessage)

        viewModel.requestAccountExport()
        runCurrent()
        assertEquals("threadmind-export.json", viewModel.state.value.pendingExport?.fileName)

        viewModel.deleteAccount()
        runCurrent()
        assertEquals(true, submissions.accountDeleted)
        assertEquals(true, viewModel.state.value.accountDeleted)
    }

    @Test fun `provider conflict requires explicit second approval for the same card version`() = runTest(dispatcher) {
        val provider = FakeProviderExecutor { card ->
            ProviderPreflightResult.MeetingConflicts(
                card.id,
                card.version,
                listOf(MeetingConflict("event-1", "已有会议", 1_000, 2_000)),
            )
        }
        val submissions = FakeSubmissionWorkflowRepository()
        val viewModel = MainViewModel(provider, FakeThreadMindApi(), submissions)
        viewModel.showCards(listOf(actionCard().copy(type = ActionType.CREATE_MEETING)))

        viewModel.preflightProvider("card-1")
        runCurrent()
        assertEquals(true, viewModel.state.value.providerReview is ProviderPreflightResult.MeetingConflicts)
        assertEquals(emptySet<String>(), viewModel.state.value.providerReviewedVersions)

        viewModel.approveProviderReview()
        runCurrent()
        assertEquals(setOf("card-1:1"), viewModel.state.value.providerReviewedVersions)
        assertEquals(setOf("card-1:1"), submissions.reviewedVersions)
    }

    @Test fun `duplicate contact selection creates a new update-contact version`() = runTest(dispatcher) {
        val change = ContactFieldChange("email", "raw-1", oldValue = null, newValue = "chen@example.com")
        val candidate = ContactCandidate("contact-1", "raw-1", "Chen", "local", null, "姓名匹配，需人工确认", listOf(change))
        val provider = FakeProviderExecutor { card ->
            ProviderPreflightResult.ContactCandidates(card.id, card.version, listOf(candidate), createContact = true)
        }
        val viewModel = MainViewModel(provider, FakeThreadMindApi(), FakeSubmissionWorkflowRepository())
        viewModel.showCards(listOf(actionCard()))

        viewModel.preflightProvider("card-1")
        runCurrent()
        viewModel.convertContactToUpdate(candidate)
        runCurrent()

        assertEquals(ActionType.UPDATE_CONTACT, viewModel.state.value.cards.single().type)
        assertEquals(2, viewModel.state.value.cards.single().version)
        assertEquals("contact-1", viewModel.state.value.cards.single().fields["targetContactId"])
        assertEquals(emptySet<String>(), viewModel.state.value.providerReviewedVersions)
    }

    @Test fun `device target selection writes the actual account fields into a new card version`() = runTest(dispatcher) {
        val target = ProviderTarget(
            targetAccountId = "person@example.com",
            label = "person@example.com · contacts",
            fieldUpdates = mapOf("accountName" to "person@example.com", "accountType" to "contacts"),
        )
        val viewModel = MainViewModel(
            FakeProviderExecutor(availableTargets = listOf(target)),
            FakeThreadMindApi(),
            FakeSubmissionWorkflowRepository(),
        )
        viewModel.showCards(listOf(actionCard()))

        viewModel.loadProviderTargets("card-1")
        runCurrent()
        assertEquals(listOf(target), viewModel.state.value.providerTargetSelection?.targets)

        viewModel.selectProviderTarget(target)
        runCurrent()
        val updated = viewModel.state.value.cards.single()
        assertEquals(2, updated.version)
        assertEquals("person@example.com", updated.targetAccountId)
        assertEquals("contacts", updated.fields["accountType"])
        assertEquals(null, viewModel.state.value.providerTargetSelection)
    }
}

private class FakeSubmissionWorkflowRepository(
    private val restored: SubmissionProgress? = null,
) : SubmissionWorkflowRepository {
    val receipts = mutableListOf<ActionReceiptRequest>()
    val deletedSubmissionIds = mutableListOf<String>()
    var writtenExportUri: Uri? = null
    var accountDeleted = false
    val reviewedVersions = mutableSetOf<String>()

    override suspend fun submit(uri: Uri, submissionId: String, source: String, supplementalText: String) = error("unused")
    override suspend fun refresh(submissionId: String): SubmissionProgress = error("unused")
    override suspend fun restoreLatest(): SubmissionProgress? = restored
    override suspend fun edit(
        cardId: String,
        expectedVersion: Int,
        fields: Map<String, String>,
        targetAccountId: String,
        resolvedValidationIssues: List<String>,
        type: ActionType?,
    ) = ActionCardPolicy.edit(
        actionCard().copy(version = expectedVersion, type = type ?: actionCard().type),
        fields,
        resolvedValidationIssues.toSet(),
    ).copy(targetAccountId = targetAccountId)
    override suspend fun confirm(cardId: String, expectedVersion: Int) = ActionCardPolicy.confirm(actionCard().copy(version = expectedVersion))
    override suspend fun cancel(cardId: String) = Unit
    override suspend fun reportExecution(cardId: String, request: ActionReceiptRequest) { receipts += request }
    override suspend fun markProviderReviewed(cardId: String, version: Int) { reviewedVersions += "$cardId:$version" }
    override suspend fun deleteSubmission(submissionId: String) { deletedSubmissionIds += submissionId }
    override suspend fun clearMemories(): Int = 1
    override suspend fun prepareAccountExport() = AccountExportPayload("request-1", "threadmind-export.json", "{\"format\":\"threadmind-export-v1\"}")
    override suspend fun writeAccountExport(uri: Uri, payload: AccountExportPayload) { writtenExportUri = uri }
    override suspend fun deleteAccount() { accountDeleted = true }
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
    var lastMemorySearch: String? = null
    var lastMemorySubjectRef: String? = null
    var lastMemoryType: String? = null
    var lastMemoryCreatedFrom: String? = null

    override suspend fun listMemories(
        search: String?,
        subjectRef: String?,
        type: String?,
        createdFrom: String?,
        createdTo: String?,
    ): MemoryListResponse {
        lastMemorySearch = search
        lastMemorySubjectRef = subjectRef
        lastMemoryType = type
        lastMemoryCreatedFrom = createdFrom
        return MemoryListResponse(listOfNotNull(memory))
    }

    override suspend fun listInsights(submissionId: String?) = InsightListResponse(listOf(insightBundle()))

    override suspend fun createSubmission(image: MultipartBody.Part, submissionId: RequestBody, source: RequestBody, supplementalText: RequestBody?): SubmissionResponse = error("unused")
    override suspend fun getSubmission(id: String): SubmissionResponse = error("unused")
    override suspend fun getExtraction(id: String): ExtractionResponse = error("unused")
    override suspend fun deleteSubmission(id: String): Response<Unit> = Response.success(Unit)
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

    override suspend fun clearMemories() = ClearMemoriesResponse(1)
    override suspend fun exportAccount(): ResponseBody = "{}".toResponseBody()
    override suspend fun deleteAccount(): Response<Unit> = Response.success(Unit)
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

private fun insightBundle() = InsightBundleResponse(
    id = "insight-1",
    submissionId = "submission-1",
    actionReceiptIds = listOf("receipt-1"),
    items = listOf(
        InsightItemResponse(
            kind = "new_development",
            title = "已完成确认的行动",
            explanation = "联系人已写入系统。",
            epistemicStatus = "fact",
            confidence = 1.0,
            evidenceRefs = listOf("receipt:receipt-1"),
            evidence = listOf(EvidenceRefResponse("receipt:receipt-1", excerpt = "Contacts Provider 返回 record-1", confidence = 1.0)),
        ),
    ),
    generatedAt = "2026-08-31T00:00:00Z",
)

private class FakeProviderExecutor(
    private val availableTargets: List<ProviderTarget> = emptyList(),
    private val inspection: (ActionCard) -> ProviderPreflightResult = { ProviderPreflightResult.Clear(it.id, it.version) },
) : ProviderExecutor {
    var executionCount = 0

    override suspend fun execute(snapshot: ConfirmedActionSnapshot): ProviderResult {
        executionCount += 1
        return ProviderResult.Succeeded("record-1")
    }

    override suspend fun inspect(card: ActionCard) = inspection(card)

    override suspend fun targets(card: ActionCard) = availableTargets

    override fun updateFields(candidate: ContactCandidate): Map<String, String> = mapOf(
        "targetContactId" to candidate.contactId,
        "changes" to "[]",
    )
}
