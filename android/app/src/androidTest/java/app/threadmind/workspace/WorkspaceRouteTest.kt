package app.threadmind.workspace

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.threadmind.MainViewModel
import app.threadmind.WorkspaceRoute
import app.threadmind.auth.AuthRepository
import app.threadmind.auth.UnavailableAuthRepository
import app.threadmind.data.local.ThreadMindDatabase
import app.threadmind.domain.*
import app.threadmind.network.*
import app.threadmind.provider.*
import app.threadmind.ui.theme.ThreadMindTheme
import app.threadmind.work.*
import org.junit.*
import java.io.File

/** Synthetic UI fixtures only. No production API or device provider writes. */
class WorkspaceRouteTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: ThreadMindDatabase
    private lateinit var model: MainViewModel
    private val id = "00000000-0000-4000-8000-000000000001"
    private val date = "2026-09-03T00:00:00Z"

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ThreadMindDatabase::class.java).build()
        val auth = object : AuthRepository by UnavailableAuthRepository("test") { override fun currentUserId() = "qa" }
        val evidence = EvidenceRefResponse(id, "e1", "Alex：请用 alex@example.com 联系我。", .95)
        val api = object : ThreadMindApi by UnavailableThreadMindApi("Unexpected mutation in UI test") {
            override suspend fun listSubmissions(view: String, limit: Int, cursor: String?) = SubmissionHistoryResponse(listOf(
                SubmissionSummaryResponse(id, date, date, "in_app", "ready", mapOf("ready" to 1))))
            override suspend fun getSubmission(id: String) = SubmissionResponse(id, "image/png", 100, source = "in_app", status = "ready", createdAt = date, updatedAt = date)
            override suspend fun getExtraction(id: String) = ExtractionResponse("extraction", id, listOf(ExtractionMessageResponse("m1", 0, "请用 alex@example.com 联系我。", "Alex", .95)), emptyList(), date)
            override suspend fun listActionCards(id: String) = ActionCardListResponse(listOf(ActionCardResponse(
                "card", id, "create_contact", 1, mapOf("displayName" to kotlinx.serialization.json.JsonPrimitive("Alex"), "contactMethod" to kotlinx.serialization.json.JsonPrimitive("alex@example.com")),
                emptyList(), targetAccountId = "local", status = "ready", blockers = emptyList())))
            override suspend fun listMemories(search: String?, subjectRef: String?, type: String?, createdFrom: String?, createdTo: String?) = MemoryListResponse(listOf(
                MemoryRecordResponse("memory", emptyList(), "profile", "Alex 的联系邮箱是 alex@example.com。", "fact", .95, "normal", listOf("e1"), listOf(evidence), date, date, 1, status = "active")))
            override suspend fun listInsights(submissionId: String?) = InsightListResponse(listOf(
                InsightBundleResponse("insight", id, emptyList(), listOf(InsightItemResponse("suggestion", "沟通方式已经明确", "对话中已提供联系邮箱，后续沟通可以使用该地址。", "inference", .9,
                    listOf("e1"), listOf(evidence), "下次联系前，核对邮箱是否仍然有效。")), date)))
        }
        val scheduler = object : WorkflowWorkScheduler {
            override fun enqueueSubmission(accountId: String, submissionId: String) = Unit
            override fun enqueueReceipt(accountId: String, receiptId: String) = Unit
            override fun cancelSubmission(accountId: String, submissionId: String) = Unit
            override fun cancelReceipt(accountId: String, receiptId: String) = Unit
            override fun cancelAccount(accountId: String) = Unit
        }
        val provider = object : ProviderExecutor {
            override suspend fun execute(snapshot: ConfirmedActionSnapshot): ProviderResult = error("Device writes are forbidden in this test")
            override suspend fun inspect(card: ActionCard) = ProviderPreflightResult.Clear(card.id, card.version)
            override fun updateFields(candidate: ContactCandidate) = emptyMap<String, String>()
        }
        val repository = AndroidSubmissionWorkflowRepository(context, api, auth, database.workflowDao(), scheduler, WorkflowSyncEngine(auth, database.workflowDao(), api))
        compose.runOnIdle { model = MainViewModel(provider, api, repository); model.switchAccount("qa") }
    }

    @After fun teardown() { compose.runOnIdle { model.switchAccount(null) }; database.close() }

    @Test fun navigationDraftProtectionHistoryAndSavedRoute() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ThreadMindTheme { WorkspaceRoute(model, false, null, {}, {}, {}) } }
        compose.waitUntil(10_000) { model.state.value.history.isNotEmpty() }
        compose.onNodeWithTag("new_analysis").performClick()
        compose.onNodeWithTag("analysis_context").performScrollTo().performTextInput("Unsaved QA draft")
        compose.onNodeWithTag("workspace_back").performClick()
        compose.onNodeWithText("放弃未保存的内容？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("analysis_context").performScrollTo().assertTextContains("Unsaved QA draft")
        compose.onNodeWithTag("workspace_back").performClick()
        compose.onNodeWithText("放弃并离开").performClick()
        compose.onNodeWithTag("tab_ACTIONS").performClick()
        compose.onNodeWithText("全部记录").performClick()
        compose.waitUntil(10_000) { !model.state.value.isHistoryLoading }
        compose.onNodeWithText("1 张行动卡 · 1 张需要处理").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.state.value.cards.isNotEmpty() }
        compose.onNodeWithText("待审核行动").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("分析详情").assertIsDisplayed()
        compose.onNodeWithTag("workspace_back").performClick()
        compose.onNodeWithText("行动与记录").assertIsDisplayed()
        compose.onNodeWithTag("new_analysis").performClick()
        compose.onNodeWithTag("analysis_context").performScrollTo().assert(hasText("Unsaved QA draft").not())
    }

    @Test fun primaryPagesHaveLightAndDarkVisualEvidence() {
        var dark by mutableStateOf(false)
        compose.setContent { ThreadMindTheme(darkTheme = dark) { WorkspaceRoute(model, false, null, {}, {}, {}) } }
        compose.waitUntil(10_000) { model.state.value.memories.isNotEmpty() && model.state.value.insights.isNotEmpty() }
        listOf(false, true).forEach { night ->
            compose.runOnIdle { dark = night }
            listOf("OVERVIEW", "ACTIONS", "MEMORIES", "INSIGHTS").forEach { tab ->
                compose.onNodeWithTag("tab_$tab").performClick()
                capture("${tab.lowercase()}-${if (night) "dark" else "light"}-synthetic")
            }
            compose.onNodeWithTag("workspace_settings").performClick()
            compose.onAllNodesWithText("账户与设置").onFirst().assertIsDisplayed()
            capture("settings-${if (night) "dark" else "light"}-synthetic")
            compose.onNodeWithTag("workspace_back").performClick()
        }
    }

    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "workspace-qa").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
