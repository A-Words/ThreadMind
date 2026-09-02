package app.threadmind

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.threadmind.auth.AuthFeedback
import app.threadmind.domain.ActionType
import app.threadmind.ui.workspace.*
import kotlinx.coroutines.launch

@Composable
fun WorkspaceRoute(
    viewModel: MainViewModel,
    isAuthLoading: Boolean,
    authFeedback: AuthFeedback?,
    onClearAuthFeedback: () -> Unit,
    onManageAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(WorkspaceTab.OVERVIEW.name) }
    var stack by rememberSaveable { mutableStateOf(listOf("main")) }
    val route = stack.last()
    val tab = WorkspaceTab.valueOf(tabName)
    val holder = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var dirty by remember { mutableStateOf(false) }
    var discardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var destructive by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionAction by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun navigate(action: () -> Unit) {
        if (dirty) discardAction = action else { dirty = false; action() }
    }
    fun push(value: String) { navigate { stack = stack + value } }
    fun startAnalysis() { navigate { viewModel.clearSelectedImage(); holder.removeState("new"); stack = stack + "new" } }
    fun back() { navigate { if (route == "new") viewModel.clearSelectedImage(); stack = stack.dropLast(1).ifEmpty { listOf("main") } } }
    fun openSubmission(id: String) { navigate { viewModel.openSubmission(id); stack = stack + "submission:$id" } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.importImage(uri)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), viewModel::saveAccountExport)
    LaunchedEffect(state.pendingExport?.requestId) { state.pendingExport?.let { export.launch(it.fileName) } }
    LaunchedEffect(Unit) { viewModel.checkBackend() }
    LaunchedEffect(state.selectedImage) { if (state.selectedImage != null && route != "new") navigate { stack = stack + "new" } }
    LaunchedEffect(route) {
        if (route.startsWith("submission:") && state.submissionId != route.substringAfter(':')) viewModel.openSubmission(route.substringAfter(':'))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.onForeground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(authFeedback) { authFeedback?.let { snackbar.showSnackbar(it.message); onClearAuthFeedback() } }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(userMessage(it)); viewModel.clearMessage() } }
    LaunchedEffect(state.dataMessage) { state.dataMessage?.let { snackbar.showSnackbar(presentationMessage(it)) } }
    BackHandler(route != "main") { back() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = permissionAction
        permissionAction = null
        if (action != null) {
            if (grants.isNotEmpty() && grants.values.all { it }) when (action.second) {
                "targets" -> viewModel.loadProviderTargets(action.first)
                "review" -> viewModel.preflightProvider(action.first)
                else -> scope.launch { viewModel.execute(action.first) }
            } else if (action.second == "execute") viewModel.permissionDenied(action.first) else viewModel.providerReadPermissionDenied()
        }
    }
    fun requestPermission(cardId: String, action: String) {
        val card = state.cards.singleOrNull { it.id == cardId } ?: return
        permissionAction = cardId to action
        val permissions = if (card.type == ActionType.CREATE_MEETING) {
            if (action == "execute") arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) else arrayOf(Manifest.permission.READ_CALENDAR)
        } else {
            if (action == "execute") arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS) else arrayOf(Manifest.permission.READ_CONTACTS)
        }
        permissionLauncher.launch(permissions)
    }

    val detailTitle = when {
        route == "main" -> null
        route == "settings" -> "账户与设置"
        route == "new" -> "新建分析"
        route.startsWith("submission:") -> "分析详情"
        route.startsWith("card:") -> "审核行动"
        route.startsWith("memory:") -> "记忆详情"
        else -> "洞察详情"
    }
    WorkspaceShell(tab, detailTitle, snackbar,
        onTab = { next -> navigate { tabName = next.name } }, onBack = ::back,
        onSettings = { push("settings") }, onNew = ::startAnalysis) {
        holder.SaveableStateProvider(if (route == "main") "main:$tabName" else route) {
            when {
                route == "main" -> when (tab) {
                    WorkspaceTab.OVERVIEW -> OverviewPage(state, ::startAnalysis, ::openSubmission,
                        { id, index -> push("insight:$id:$index") }, { tabName = WorkspaceTab.ACTIONS.name },
                        { tabName = WorkspaceTab.INSIGHTS.name }, { viewModel.refreshHistory() })
                    WorkspaceTab.ACTIONS -> HistoryPage(state, viewModel::setHistoryView, { viewModel.refreshHistory() }, { viewModel.refreshHistory(true) }, ::openSubmission)
                    WorkspaceTab.MEMORIES -> MemoriesPage(state, viewModel) { push("memory:$it") }
                    WorkspaceTab.INSIGHTS -> InsightsPage(state, viewModel::refreshInsights) { id, index -> push("insight:$id:$index") }
                }
                route == "new" -> NewAnalysisPage(state, { picker.launch(arrayOf("image/*")) }, viewModel::setSupplementalText,
                    onUpload = {
                        dirty = false
                        viewModel.submitForAnalysis()
                        viewModel.state.value.submissionId?.let {
                            holder.removeState("new")
                            stack = stack.dropLast(1) + "submission:$it"
                        }
                    }, onCancel = ::back, onDirty = { dirty = it })
                route.startsWith("submission:") -> SubmissionPage(state, viewModel::refreshSubmission, ::startAnalysis, { push("card:$it") }, { destructive = "submission" })
                route.startsWith("card:") -> {
                    val card = state.cards.singleOrNull { it.id == route.substringAfter(':') }
                    if (card == null) WorkspaceList { item { EmptyPanel("行动暂不可用", "请返回分析记录重新加载。") } }
                    else ActionDetailPage(card, card.id in state.pendingCardIds, card.id in state.readOnlyCardIds,
                        card.id in state.pendingReceipts, "${card.id}:${card.version}" in state.providerReviewedVersions,
                        card.id in state.pendingProviderReviewIds,
                        onSave = { fields, issues -> viewModel.editCard(card.id, fields, card.targetAccountId.orEmpty(), issues) },
                        onSelectTarget = { requestPermission(card.id, "targets") }, onPreflight = { requestPermission(card.id, "review") },
                        onConfirm = { viewModel.confirm(card.id) }, onExecute = { requestPermission(card.id, "execute") },
                        onCancel = { viewModel.cancelCard(card.id) }, onRetryReceipt = { viewModel.retryReceipt(card.id) }, onDirty = { dirty = it })
                }
                route.startsWith("memory:") -> {
                    val memory = state.memories.singleOrNull { it.id == route.substringAfter(':') }
                    val revision = state.memories.firstOrNull { it.supersedesId == route.substringAfter(':') }
                    LaunchedEffect(revision?.id) { revision?.let { dirty = false; stack = stack.dropLast(1) + "memory:${it.id}" } }
                    if (memory == null) WorkspaceList { item { EmptyPanel("这条记忆已更新或删除", "返回列表查看最新记录。") } }
                    else MemoryDetailPage(memory, memory.id in state.pendingMemoryIds, { viewModel.reviseMemory(memory.id, it) },
                        { destructive = "memory:${memory.id}" }, ::openSubmission, { dirty = it })
                }
                route.startsWith("insight:") -> {
                    val parts = route.split(':')
                    val bundle = state.insights.singleOrNull { it.id == parts[1] }
                    if (bundle == null) WorkspaceList { item { EmptyPanel("洞察暂不可用", "来源可能已删除，请返回列表刷新。") } }
                    else InsightDetailPage(bundle, parts.last().toInt(), ::openSubmission)
                }
                route == "settings" -> SettingsPage(state,
                    { if (!isAuthLoading) onManageAccount() }, { if (!state.isDataOperationPending) viewModel.requestAccountExport() },
                    { destructive = "signout" }, { destructive = "clear" }, { destructive = "account" })
            }
        }
    }
    LaunchedEffect(state.submissionId, state.isDataOperationPending) {
        if (route.startsWith("submission:") && state.submissionId == null && state.dataMessage == "本次提交及其派生数据已删除") stack = listOf("main")
    }
    if (discardAction != null) AlertDialog(onDismissRequest = { discardAction = null }, title = { Text("放弃未保存的内容？") },
        text = { Text("离开后，本次尚未保存的修改不会提交。") },
        confirmButton = { TextButton(onClick = {
            val action = discardAction; discardAction = null; dirty = false
            holder.removeState(route); action?.invoke()
        }) { Text("放弃并离开") } }, dismissButton = { TextButton(onClick = { discardAction = null }) { Text("继续编辑") } })
    destructive?.let { operation ->
        val title = when { operation == "account" -> "永久删除账户？"; operation == "clear" -> "清空全部记忆？"; operation == "submission" -> "删除这次分析？"; operation == "signout" -> "退出登录？"; else -> "删除这条记忆？" }
        val body = when (operation) {
            "account" -> "账户、分析记录、记忆与洞察将被永久删除。系统通讯录和日历不受影响。"
            "clear" -> "所有活动记忆将停止参与后续建议，分析记录和洞察历史仍保留。"
            "submission" -> "相关行动卡、回执、记忆来源和洞察历史将被删除，无法恢复。"
            "signout" -> "退出后不会显示当前账户的数据。"
            else -> "这条记忆将不再用于后续洞察与建议。"
        }
        AlertDialog(onDismissRequest = { destructive = null }, title = { Text(title) }, text = { Text(body) },
            confirmButton = { TextButton(enabled = !state.isDataOperationPending, onClick = {
                destructive = null
                when (operation) {
                    "account" -> viewModel.deleteAccount()
                    "clear" -> viewModel.clearAllMemories()
                    "submission" -> viewModel.deleteCurrentSubmission()
                    "signout" -> onSignOut()
                    else -> { viewModel.deleteMemory(operation.substringAfter(':')); dirty = false; stack = stack.dropLast(1) }
                }
            }) { Text(if (operation == "signout") "退出" else "确认删除") } }, dismissButton = { TextButton(onClick = { destructive = null }) { Text("取消") } })
    }
    state.providerReview?.let { ProviderReviewDialog(it, viewModel::approveProviderReview, viewModel::convertContactToUpdate, viewModel::dismissProviderReview) }
    state.providerTargetSelection?.let { ProviderTargetDialog(it.targets, viewModel::selectProviderTarget, viewModel::dismissProviderTargets) }
}

private fun userMessage(message: String): String = when {
    message.contains("http", ignoreCase = true) || message.contains("java.") || message.contains("Exception") -> "操作暂未完成，请检查网络后重试。"
    message.startsWith("已写入系统，记录") -> "已成功写入系统"
    else -> message
}
