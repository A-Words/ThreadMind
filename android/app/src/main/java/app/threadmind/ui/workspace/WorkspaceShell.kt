package app.threadmind.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import app.threadmind.ui.theme.ThreadMindTheme

enum class WorkspaceTab(val title: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Outlined.Dashboard),
    ACTIONS("行动", Icons.Outlined.TaskAlt),
    MEMORIES("记忆", Icons.Outlined.Bookmarks),
    INSIGHTS("洞察", Icons.Outlined.AutoAwesome),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceShell(
    tab: WorkspaceTab,
    detailTitle: String? = null,
    snackbarHostState: SnackbarHostState,
    onTab: (WorkspaceTab) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onNew: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val railItemHeight = if (maxHeight < 480.dp) 64.dp else 80.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(title = { Text(detailTitle ?: "ThreadMind", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { if (detailTitle != null) IconButton(onClick = onBack, modifier = Modifier.testTag("workspace_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    } },
                    actions = { if (detailTitle == null) IconButton(onClick = onSettings, modifier = Modifier.testTag("workspace_settings")) {
                        Icon(Icons.Outlined.AccountCircle, "账户与设置")
                    } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            },
            bottomBar = { if (!wide && detailTitle == null) NavigationBar {
                WorkspaceTab.entries.forEach { destination ->
                    NavigationBarItem(selected = tab == destination, onClick = { onTab(destination) },
                        icon = { Icon(destination.icon, null) }, label = { Text(destination.title) },
                        modifier = Modifier.testTag("tab_${destination.name}"))
                }
            } },
            floatingActionButton = { if (detailTitle == null) ExtendedFloatingActionButton(
                onClick = onNew, icon = { Icon(Icons.Outlined.AddPhotoAlternate, null) },
                text = { Text("分析截图") }, modifier = Modifier.testTag("new_analysis")) },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide && detailTitle == null) NavigationRail(
                    Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    WorkspaceTab.entries.forEach { destination ->
                        NavigationRailItem(selected = tab == destination, onClick = { onTab(destination) },
                            icon = { Icon(destination.icon, null) }, label = { Text(destination.title) },
                            modifier = Modifier.height(railItemHeight).testTag("tab_${destination.name}"))
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().imePadding()) { content() }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ShellPreview() = ThreadMindTheme {
    WorkspaceShell(WorkspaceTab.OVERVIEW, snackbarHostState = SnackbarHostState(), onTab = {}, onBack = {}, onSettings = {}, onNew = {}) {
        WorkspaceList { item { PageHeader("把对话里的重要事，带到下一步", "有依据的建议，由你决定如何行动。") } }
    }
}
