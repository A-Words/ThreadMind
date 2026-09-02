package app.threadmind.workspace

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.threadmind.MainUiState
import app.threadmind.domain.*
import app.threadmind.ui.theme.ThreadMindTheme
import app.threadmind.ui.workspace.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WorkspaceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun analysisDraftSurvivesSavedInstanceStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ThreadMindTheme { NewAnalysisPage(MainUiState(), {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("analysis_context").performScrollTo().performTextInput("Synthetic draft to restore")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("analysis_context").performScrollTo().assertTextContains("Synthetic draft to restore")
    }

    @Test fun actionEditsSurviveRestorationButStillRequireSaving() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ThreadMindTheme { ActionDetailPage(card().copy(status = ActionStatus.READY), false, false, false, true, false,
            { _, _ -> }, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNode(hasText("Alex") and hasSetTextAction()).performScrollTo().performTextReplacement("Alex QA")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Alex QA", substring = false).performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("保存修改"))
        compose.onNodeWithText("保存修改").assertIsDisplayed()
        compose.onAllNodesWithText("授权并写入系统").assertCountEquals(0)
    }

    @Test fun narrowLayoutAtTwoHundredPercentFontKeepsFiltersAndUploadReachable() {
        var filter = ""
        var analysis by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                ThreadMindTheme {
                    Box(Modifier.requiredSize(320.dp, 640.dp)) {
                        if (analysis) NewAnalysisPage(MainUiState(), {}, {}, {}, {}, {})
                        else HistoryPage(MainUiState(), { filter = it }, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("全部记录").performScrollTo().performClick()
        assertEquals("all", filter)
        capture("history-large-font")
        compose.runOnIdle { analysis = true }
        compose.onNodeWithTag("analysis_context").performScrollTo().performTextInput("Synthetic context")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("upload_analysis"))
        compose.onNodeWithTag("upload_analysis").assertIsDisplayed().assertIsNotEnabled()
        capture("analysis-large-font")
    }

    @Test fun fourDestinationsAndSettingsAreReachableWithoutDangerousHomeActions() {
        var tab by mutableStateOf(WorkspaceTab.OVERVIEW)
        var settings = false
        compose.setContent { ThreadMindTheme { WorkspaceShell(tab, snackbarHostState = SnackbarHostState(), onTab = { tab = it }, onBack = {}, onSettings = { settings = true }, onNew = {}) {
            OverviewPage(MainUiState(), {}, {}, { _, _ -> }, {}, {}, {})
        } } }
        WorkspaceTab.entries.forEach { compose.onNodeWithTag("tab_${it.name}").performClick(); assertEquals(it, tab) }
        compose.onAllNodesWithText("永久删除账户").assertCountEquals(0)
        compose.onNodeWithTag("workspace_settings").performClick()
        assertTrue(settings)
        compose.onNodeWithTag("tab_OVERVIEW").performClick()
        capture("overview-light")
    }

    @Test fun overviewInDarkModeHasRealEmptyStates() {
        compose.setContent { ThreadMindTheme(darkTheme = true) { WorkspaceShell(WorkspaceTab.OVERVIEW, snackbarHostState = SnackbarHostState(), onTab = {}, onBack = {}, onSettings = {}, onNew = {}) {
            OverviewPage(MainUiState(), {}, {}, { _, _ -> }, {}, {}, {})
        } } }
        compose.onNodeWithText("从一张聊天截图开始").assertIsDisplayed()
        capture("overview-dark")
    }

    @Test fun newAnalysisRequiresASelectedImageAndExplicitUpload() {
        var uploads = 0
        compose.setContent { ThreadMindTheme { NewAnalysisPage(MainUiState(), {}, {}, { uploads++ }, {}, {}) } }
        compose.onNodeWithTag("upload_analysis").performScrollTo().assertIsNotEnabled()
        assertEquals(0, uploads)
        capture("analysis-light")
    }

    @Test fun sourceTranscriptIsCollapsedUntilRequested() {
        val state = MainUiState(submissionId = "synthetic", submissionStatus = "ready", analysis = app.threadmind.network.ExtractionResponse(
            "extraction", "synthetic", listOf(app.threadmind.network.ExtractionMessageResponse("m1", 0, "Synthetic message", "Alex", .9)), emptyList(), "2026-09-01T00:00:00Z"))
        compose.setContent { ThreadMindTheme { SubmissionPage(state, {}, {}, {}, {}) } }
        compose.onAllNodesWithText("Synthetic message").assertCountEquals(0)
        compose.onNodeWithText("查看对话转录与依据").performScrollTo().performClick()
        compose.onNodeWithText("Synthetic message").performScrollTo().assertIsDisplayed()
    }

    @Test fun pendingReceiptHasNoSecondExecutionAction() {
        var executions = 0
        compose.setContent { ThreadMindTheme { ActionDetailPage(card(), false, false, true, true, false,
            { _, _ -> }, {}, {}, {}, { executions++ }, {}, {}, {}) } }
        compose.onNodeWithText("同步回执").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("授权并写入系统").assertCountEquals(0)
        assertEquals(0, executions)
    }

    @Test fun remoteConfirmedCardIsReadOnly() {
        compose.setContent { ThreadMindTheme { ActionDetailPage(card(), false, true, false, false, false,
            { _, _ -> }, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onAllNodesWithText("确认当前版本").assertCountEquals(0)
        compose.onAllNodesWithText("授权并写入系统").assertCountEquals(0)
    }

    private fun card() = ActionCard("card", "synthetic", ActionType.CREATE_CONTACT, 1,
        mapOf("displayName" to "Alex", "contactMethod" to "alex@example.com"), emptyList(), emptyMap(), emptyList(), "local", ActionStatus.CONFIRMED, emptyList())

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "workspace-qa").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
