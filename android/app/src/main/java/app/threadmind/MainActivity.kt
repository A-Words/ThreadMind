package app.threadmind

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.threadmind.auth.AuthActions
import app.threadmind.auth.AuthFeedback
import app.threadmind.auth.AuthScreen
import app.threadmind.auth.AuthStep
import app.threadmind.auth.AuthViewModel
import app.threadmind.domain.ActionCard
import app.threadmind.domain.ActionStatus
import app.threadmind.domain.ActionType
import app.threadmind.domain.actionFieldSpecs
import app.threadmind.network.MemoryRecordResponse
import app.threadmind.network.InsightBundleResponse
import app.threadmind.provider.ProviderPreflightResult
import app.threadmind.provider.ProviderTarget
import app.threadmind.ui.theme.ThreadMindTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent { ThreadMindTheme { ThreadMindRoot(authViewModel, viewModel) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        viewModel.importSharedImage(uri)
    }
}

@Composable
private fun ThreadMindRoot(authViewModel: AuthViewModel, mainViewModel: MainViewModel) {
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val mainState by mainViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(mainState.accountDeleted) {
        if (mainState.accountDeleted) {
            authViewModel.accountDeleted()
            mainViewModel.consumeAccountDeleted()
        }
    }
    if (authState.isInitializing) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (authState.step == AuthStep.AUTHENTICATED) {
        ThreadMindScreen(
            mainViewModel,
            isAuthLoading = authState.isLoading,
            authFeedback = authState.feedback,
            onClearAuthFeedback = authViewModel::clearFeedback,
            onManageAccount = authViewModel::beginPasswordUpdate,
            onSignOut = authViewModel::signOut,
        )
    } else {
        AuthScreen(
            state = authState,
            actions = AuthActions(
                onEmailChange = authViewModel::setEmail,
                onPasswordChange = authViewModel::setPassword,
                onConfirmPasswordChange = authViewModel::setConfirmPassword,
                onTokenChange = authViewModel::setToken,
                onPrivacyAcceptedChange = authViewModel::setPrivacyAccepted,
                onIntentChange = authViewModel::setIntent,
                onMethodChange = authViewModel::setMethod,
                onSubmitCredentials = authViewModel::submitCredentials,
                onStartPasswordRecovery = authViewModel::startPasswordRecovery,
                onVerifyCode = authViewModel::verifyCode,
                onResendCode = authViewModel::resendCode,
                onUpdatePassword = authViewModel::updatePassword,
                onCancelFlow = authViewModel::cancelFlow,
                onClearFeedback = authViewModel::clearFeedback,
            ),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ThreadMindScreen(
    viewModel: MainViewModel,
    isAuthLoading: Boolean,
    authFeedback: AuthFeedback?,
    onClearAuthFeedback: () -> Unit,
    onManageAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingProviderPermission by remember { mutableStateOf<Pair<String, ProviderPermissionAction>?>(null) }
    var memoryToDelete by remember { mutableStateOf<MemoryRecordResponse?>(null) }
    var confirmSubmissionDelete by remember { mutableStateOf(false) }
    var confirmMemoryClear by remember { mutableStateOf(false) }
    var confirmAccountDelete by remember { mutableStateOf(false) }
    var memoryTypeMenuExpanded by remember { mutableStateOf(false) }
    var memoryTimeMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.checkBackend() }
    LaunchedEffect(authFeedback) {
        val feedback = authFeedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(feedback.message)
        onClearAuthFeedback()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), viewModel::importImage)
    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
        viewModel::saveAccountExport,
    )
    LaunchedEffect(state.pendingExport?.requestId) {
        state.pendingExport?.let { exportDocument.launch(it.fileName) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val pending = pendingProviderPermission
        pendingProviderPermission = null
        if (pending != null) {
            if (grants.values.all { it }) {
                if (pending.second == ProviderPermissionAction.PREFLIGHT) viewModel.preflightProvider(pending.first)
                else if (pending.second == ProviderPermissionAction.TARGETS) viewModel.loadProviderTargets(pending.first)
                else scope.launch { viewModel.execute(pending.first) }
            } else if (pending.second != ProviderPermissionAction.EXECUTE) {
                viewModel.providerReadPermissionDenied()
            } else {
                viewModel.permissionDenied(pending.first)
            }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("ThreadMind") },
                actions = {
                    TextButton(onClick = onManageAccount, enabled = !isAuthLoading) { Text("账户") }
                    TextButton(onClick = onSignOut, enabled = !isAuthLoading) { Text("退出") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("把聊天变成可确认的行动，以及有依据的下一步建议。", style = MaterialTheme.typography.titleMedium)
                Text(state.backendMessage, modifier = Modifier.padding(top = 8.dp))
                if (state.backendStatus == BackendStatus.FAILED) {
                    TextButton(onClick = viewModel::checkBackend) { Text("重试服务端连接") }
                }
                Button(onClick = { picker.launch("image/*") }, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                    Text(if (state.selectedImage == null) "选择聊天截图" else "已选择截图")
                }
                OutlinedTextField(
                    value = state.supplementalText,
                    onValueChange = viewModel::setSupplementalText,
                    label = { Text("补充说明（可选）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                state.selectedImage?.let {
                    Text(
                        "将把所选截图${if (state.supplementalText.isBlank()) "" else "和补充说明"}上传到云端模型分析；原图处理完成后删除。你可以在上传前取消。",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = viewModel::submitForAnalysis, enabled = !state.isSubmissionPending) {
                            Text(if (state.isSubmissionPending) "处理中…" else "同意上传并分析")
                        }
                        TextButton(onClick = viewModel::clearSelectedImage, enabled = !state.isSubmissionPending) { Text("取消") }
                    }
                }
                if (state.isSubmissionPending) CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                state.submissionMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                if (state.submissionId != null && !state.isSubmissionPending && state.submissionStatus != "ready") {
                    TextButton(onClick = viewModel::refreshSubmission) { Text("刷新分析状态") }
                }
            }
            state.analysis?.let { analysis ->
                item {
                    Text("分析结果", style = MaterialTheme.typography.headlineSmall)
                    Text("请核对转录、说话人和置信度；Action Card 的每条依据都来自这里。")
                    if (analysis.messages.isEmpty()) Text("没有识别到可核对的消息文本。")
                }
                items(analysis.messages.sortedBy { it.order }, key = { "analysis:${it.id}" }) { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(message.speaker ?: "说话人不确定", style = MaterialTheme.typography.labelLarge)
                            Text(message.text)
                            Text(
                                "置信度 ${(message.confidence * 100).toInt()}%${if (message.confidence < 0.8) " · 需要重点核对" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                if (analysis.warnings.isNotEmpty()) {
                    item {
                        Text("分析警告", style = MaterialTheme.typography.titleMedium)
                        analysis.warnings.forEach { warning -> Text("• $warning") }
                    }
                }
            }
            item {
                Text("数据管理", style = MaterialTheme.typography.headlineSmall)
                Text("导出不包含原始截图；删除操作会清理云端结构化记录和设备内的 ThreadMind 缓存，不会删除已写入系统通讯录或日历的记录。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::requestAccountExport, enabled = !state.isDataOperationPending) {
                        Text("导出数据")
                    }
                    if (state.submissionId != null) {
                        TextButton(
                            onClick = { confirmSubmissionDelete = true },
                            enabled = !state.isDataOperationPending,
                        ) { Text("删除本次提交") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirmMemoryClear = true }, enabled = !state.isDataOperationPending) {
                        Text("清空全部记忆")
                    }
                    TextButton(onClick = { confirmAccountDelete = true }, enabled = !state.isDataOperationPending) {
                        Text("删除账户")
                    }
                }
                if (state.isDataOperationPending) CircularProgressIndicator()
                state.dataMessage?.let { Text(it) }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("记忆中心", style = MaterialTheme.typography.headlineSmall)
                    TextButton(
                        onClick = viewModel::refreshMemories,
                        enabled = !state.isMemoryLoading,
                    ) { Text("刷新") }
                }
                Text("你可以核对、修订或删除系统保存的记忆。修订会保留来源和历史版本。")
                OutlinedTextField(
                    value = state.memorySearch,
                    onValueChange = viewModel::setMemorySearch,
                    label = { Text("搜索记忆与来源摘录") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.memorySubjectRef,
                    onValueChange = viewModel::setMemorySubjectRef,
                    label = { Text("联系人标识（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { memoryTypeMenuExpanded = true }) {
                            Text(MEMORY_TYPES.first { it.first == state.memoryType }.second)
                        }
                        DropdownMenu(expanded = memoryTypeMenuExpanded, onDismissRequest = { memoryTypeMenuExpanded = false }) {
                            MEMORY_TYPES.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        viewModel.setMemoryType(value)
                                        memoryTypeMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { memoryTimeMenuExpanded = true }) {
                            Text(state.memoryTimeFilter.label)
                        }
                        DropdownMenu(expanded = memoryTimeMenuExpanded, onDismissRequest = { memoryTimeMenuExpanded = false }) {
                            MemoryTimeFilter.entries.forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(value.label) },
                                    onClick = {
                                        viewModel.setMemoryTimeFilter(value)
                                        memoryTimeMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::refreshMemories, enabled = !state.isMemoryLoading) { Text("应用筛选") }
                    TextButton(onClick = viewModel::clearMemoryFilters, enabled = !state.isMemoryLoading) { Text("清除") }
                }
                if (state.backendStatus == BackendStatus.CHECKING || state.isMemoryLoading) CircularProgressIndicator()
                if (state.backendStatus == BackendStatus.CONNECTED && state.memories.isEmpty()) {
                    Text("还没有活动记忆。")
                }
                state.memoryMessage?.let { Text(it) }
            }
            items(state.memories, key = MemoryRecordResponse::id) { memory ->
                MemoryCard(
                    memory = memory,
                    isPending = memory.id in state.pendingMemoryIds,
                    onSave = { viewModel.reviseMemory(memory.id, it) },
                    onDelete = { memoryToDelete = memory },
                )
            }
            items(state.cards, key = ActionCard::id) { card ->
                ActionCardReviewCard(
                    card = card,
                    isPending = card.id in state.pendingCardIds,
                    receiptPending = card.id in state.pendingReceipts,
                    providerReviewed = "${card.id}:${card.version}" in state.providerReviewedVersions,
                    providerReviewPending = card.id in state.pendingProviderReviewIds,
                    onSave = { fields, target, issues -> viewModel.editCard(card.id, fields, target, issues) },
                    onConfirm = { viewModel.confirm(card.id) },
                    onCancel = { viewModel.cancelCard(card.id) },
                    onPreflight = {
                        pendingProviderPermission = card.id to ProviderPermissionAction.PREFLIGHT
                        permissions.launch(
                            if (card.type == ActionType.CREATE_MEETING) arrayOf(Manifest.permission.READ_CALENDAR)
                            else arrayOf(Manifest.permission.READ_CONTACTS),
                        )
                    },
                    onSelectTarget = {
                        pendingProviderPermission = card.id to ProviderPermissionAction.TARGETS
                        permissions.launch(
                            if (card.type == ActionType.CREATE_MEETING) arrayOf(Manifest.permission.READ_CALENDAR)
                            else arrayOf(Manifest.permission.READ_CONTACTS),
                        )
                    },
                    onExecute = {
                        pendingProviderPermission = card.id to ProviderPermissionAction.EXECUTE
                        permissions.launch(
                            if (card.type == ActionType.CREATE_MEETING) arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                            else arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
                        )
                    },
                    onRetryReceipt = { viewModel.retryReceipt(card.id) },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("洞察与下一步", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = viewModel::refreshInsights, enabled = !state.isInsightLoading) { Text("刷新") }
                }
                Text("正式执行后洞察只引用成功回执、当前来源和有效记忆；历史洞察不会反向成为事实记忆。")
                if (state.isInsightLoading) CircularProgressIndicator()
                if (state.backendStatus == BackendStatus.CONNECTED && state.insights.isEmpty()) {
                    Text("成功执行至少一张卡片后，这里会显示有依据的洞察。")
                }
                state.insightMessage?.let { Text(it) }
            }
            items(state.insights, key = InsightBundleResponse::id) { bundle -> InsightBundleCard(bundle) }
            state.message?.let { item { Text(it) } }
        }
    }
    memoryToDelete?.let { memory ->
        AlertDialog(
            onDismissRequest = { memoryToDelete = null },
            title = { Text("删除这条记忆？") },
            text = { Text("删除后，它将不再用于后续洞察和建议。") },
            confirmButton = {
                Button(onClick = {
                    memoryToDelete = null
                    viewModel.deleteMemory(memory.id)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { memoryToDelete = null }) { Text("取消") }
            },
        )
    }
    if (confirmSubmissionDelete) {
        AlertDialog(
            onDismissRequest = { confirmSubmissionDelete = false },
            title = { Text("删除本次提交？") },
            text = { Text("相关 Action Cards、执行回执、记忆来源和洞察历史都会删除，且无法恢复。") },
            confirmButton = {
                Button(onClick = {
                    confirmSubmissionDelete = false
                    viewModel.deleteCurrentSubmission()
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { confirmSubmissionDelete = false }) { Text("取消") } },
        )
    }
    if (confirmMemoryClear) {
        AlertDialog(
            onDismissRequest = { confirmMemoryClear = false },
            title = { Text("清空全部记忆？") },
            text = { Text("所有活动记忆会立即停止参与后续召回；提交和洞察历史不会因此删除。") },
            confirmButton = {
                Button(onClick = {
                    confirmMemoryClear = false
                    viewModel.clearAllMemories()
                }) { Text("确认清空") }
            },
            dismissButton = { TextButton(onClick = { confirmMemoryClear = false }) { Text("取消") } },
        )
    }
    if (confirmAccountDelete) {
        AlertDialog(
            onDismissRequest = { confirmAccountDelete = false },
            title = { Text("永久删除账户？") },
            text = { Text("账户、截图缓存、提交、卡片、回执、记忆和洞察都会删除且无法恢复。已写入系统通讯录或日历的记录不会被删除。") },
            confirmButton = {
                Button(onClick = {
                    confirmAccountDelete = false
                    viewModel.deleteAccount()
                }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { confirmAccountDelete = false }) { Text("取消") } },
        )
    }
    state.providerReview?.let { review ->
        ProviderReviewDialog(
            review = review,
            onApprove = viewModel::approveProviderReview,
            onConvert = viewModel::convertContactToUpdate,
            onDismiss = viewModel::dismissProviderReview,
        )
    }
    state.providerTargetSelection?.let { selection ->
        ProviderTargetDialog(
            targets = selection.targets,
            onSelect = viewModel::selectProviderTarget,
            onDismiss = viewModel::dismissProviderTargets,
        )
    }
}

@Composable
private fun ActionCardReviewCard(
    card: ActionCard,
    isPending: Boolean,
    receiptPending: Boolean,
    providerReviewed: Boolean,
    providerReviewPending: Boolean,
    onSave: (Map<String, String>, String, Set<String>) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onPreflight: () -> Unit,
    onSelectTarget: () -> Unit,
    onExecute: () -> Unit,
    onRetryReceipt: () -> Unit,
) {
    var fields by remember(card.id, card.version) { mutableStateOf(card.fields) }
    var targetAccountId by remember(card.id, card.version) { mutableStateOf(card.targetAccountId.orEmpty()) }
    var resolvedIssues by remember(card.id, card.version) { mutableStateOf(emptySet<String>()) }
    val editable = card.status !in setOf(ActionStatus.EXECUTING, ActionStatus.SUCCEEDED, ActionStatus.CANCELLED)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fields["title"] ?: fields["displayName"] ?: card.type.name, style = MaterialTheme.typography.titleMedium)
            Text("版本 ${card.version} · ${card.status}")
            val specs = actionFieldSpecs(card.type)
            val knownFields = specs.mapTo(mutableSetOf()) { it.key }
            specs.filterNot { it.providerManaged && card.fields[it.key].isNullOrBlank() }.forEach { spec ->
                val field = spec.key
                val value = fields[field].orEmpty()
                val confidence = card.fieldConfidence[field]
                OutlinedTextField(
                    value = value,
                    onValueChange = { updated -> fields = fields + (field to updated) },
                    enabled = editable && !isPending && !spec.providerManaged,
                    label = {
                        Text(
                            buildString {
                                append(spec.label)
                                if (spec.required) append("（必填）")
                                if (confidence != null) append(" · ${(confidence * 100).toInt()}%${if (confidence < 0.8) " 低置信" else ""}")
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            fields.filterKeys { it !in knownFields }.toSortedMap().forEach { (field, value) ->
                val confidence = card.fieldConfidence[field]
                OutlinedTextField(
                    value = value,
                    onValueChange = { updated -> fields = fields + (field to updated) },
                    enabled = editable && !isPending,
                    label = { Text("$field${confidence?.let { " · ${(it * 100).toInt()}%${if (it < 0.8) " 低置信" else ""}" }.orEmpty()}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = targetAccountId,
                onValueChange = { targetAccountId = it },
                enabled = editable && !isPending,
                label = { Text("目标账户（必填且可修改）") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onSelectTarget, enabled = editable && !isPending && !providerReviewPending) {
                Text("从设备账户中选择")
            }
            card.validationIssues.forEach { issue ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = issue in resolvedIssues,
                        onCheckedChange = { checked ->
                            resolvedIssues = if (checked) resolvedIssues + issue else resolvedIssues - issue
                        },
                        enabled = editable && !isPending,
                    )
                    Text("需明确处理：$issue")
                }
            }
            card.blockers.filterNot { it.startsWith("validation:") }.forEach { Text("尚不能确认：$it") }
            card.evidence.forEach { Text("依据：${it.excerpt} (${(it.confidence * 100).toInt()}%)") }
            Button(onClick = onPreflight, enabled = !isPending && !providerReviewPending) {
                Text(
                    when {
                        providerReviewPending -> "检查中…"
                        providerReviewed -> "重新检查设备数据"
                        else -> "检查设备数据"
                    },
                )
            }
            Text(if (providerReviewed) "当前版本已完成 Provider 预检" else "确认前必须检查重复项、冲突和目标记录")
            val changed = fields != card.fields || targetAccountId != card.targetAccountId.orEmpty() || resolvedIssues.isNotEmpty()
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSave(fields, targetAccountId.trim(), resolvedIssues) },
                        enabled = !isPending && targetAccountId.isNotBlank() && changed,
                    ) { Text("保存修改") }
                    Button(onClick = onConfirm, enabled = !isPending && providerReviewed && card.status == ActionStatus.READY && !changed) {
                        Text("确认当前版本")
                    }
                }
                TextButton(onClick = onCancel, enabled = !isPending) { Text("取消这张卡片") }
            }
            Button(onClick = onExecute, enabled = !isPending && !receiptPending && providerReviewed && card.status == ActionStatus.CONFIRMED) {
                Text("授权并写入系统")
            }
            if (receiptPending) {
                Text("系统写入结果已知，但云端回执尚未同步；请勿再次执行。")
                Button(onClick = onRetryReceipt) { Text("重试同步回执") }
            }
        }
    }
}

@Composable
private fun ProviderReviewDialog(
    review: ProviderPreflightResult,
    onApprove: () -> Unit,
    onConvert: (app.threadmind.provider.ContactCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (review) {
        is ProviderPreflightResult.MeetingConflicts -> "发现疑似重复会议"
        is ProviderPreflightResult.ContactCandidates -> if (review.createContact) "发现可能重复的联系人" else "请选择唯一联系人"
        is ProviderPreflightResult.ContactOverwrites -> "确认联系人字段差异"
        is ProviderPreflightResult.Blocked -> "设备数据检查未通过"
        is ProviderPreflightResult.Clear -> "设备数据检查完成"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (review) {
                    is ProviderPreflightResult.MeetingConflicts -> review.items.forEach {
                        Text("${it.title} · ${java.time.Instant.ofEpochMilli(it.startsAtEpochMillis)} — ${java.time.Instant.ofEpochMilli(it.endsAtEpochMillis)}")
                    }
                    is ProviderPreflightResult.ContactCandidates -> review.items.forEach { candidate ->
                        Text("${candidate.displayName.ifBlank { "未命名联系人" }} · ${candidate.accountName ?: "本地账户"} · ${candidate.matchedBy}")
                        candidate.proposedChanges.forEach { change ->
                            Text("${change.field}：${change.oldValue ?: "未设置"} → ${change.newValue}")
                        }
                        TextButton(
                            onClick = { onConvert(candidate) },
                            enabled = candidate.proposedChanges.isNotEmpty(),
                        ) { Text("改为更新此联系人") }
                    }
                    is ProviderPreflightResult.ContactOverwrites -> review.changes.forEach { change ->
                        Text("${change.field}：${change.oldValue ?: "未设置"} → ${change.newValue}")
                    }
                    is ProviderPreflightResult.Blocked -> Text(review.message)
                    is ProviderPreflightResult.Clear -> Text("没有发现需要额外确认的问题。")
                }
            }
        },
        confirmButton = {
            when (review) {
                is ProviderPreflightResult.MeetingConflicts -> Button(onClick = onApprove) { Text("仍然保留本次会议") }
                is ProviderPreflightResult.ContactCandidates -> if (review.createContact) {
                    Button(onClick = onApprove) { Text("仍然创建新联系人") }
                }
                is ProviderPreflightResult.ContactOverwrites -> Button(onClick = onApprove) { Text("确认覆盖这些字段") }
                is ProviderPreflightResult.Blocked,
                is ProviderPreflightResult.Clear -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

@Composable
private fun ProviderTargetDialog(
    targets: List<ProviderTarget>,
    onSelect: (ProviderTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标账户") },
        text = {
            LazyColumn {
                items(targets) { target ->
                    TextButton(onClick = { onSelect(target) }, modifier = Modifier.fillMaxWidth()) {
                        Text(target.label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

private enum class ProviderPermissionAction { PREFLIGHT, TARGETS, EXECUTE }

@Composable
private fun MemoryCard(
    memory: MemoryRecordResponse,
    isPending: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var assertion by remember(memory.id, memory.assertion) { mutableStateOf(memory.assertion) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (memory.epistemicStatus == "fact") "事实" else "推断",
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedTextField(
                value = assertion,
                onValueChange = { assertion = it },
                enabled = !isPending,
                label = { Text("记忆内容") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("置信度 ${(memory.confidence * 100).toInt()}% · 第 ${memory.version} 版 · ${memory.sensitivity}")
            Text("创建时间：${memory.createdAt}")
            if (memory.sourceEvidence.isEmpty()) {
                Text("来源：${memory.sourceRefs.joinToString().ifBlank { "无" }}")
            } else {
                memory.sourceEvidence.forEach { evidence ->
                    Text("来源摘录：${evidence.excerpt} (${(evidence.confidence * 100).toInt()}%)")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(assertion) },
                    enabled = !isPending && assertion.isNotBlank() && assertion != memory.assertion,
                ) { Text(if (isPending) "处理中…" else "保存修订") }
                TextButton(onClick = onDelete, enabled = !isPending) { Text("删除") }
            }
        }
    }
}

@Composable
private fun InsightBundleCard(bundle: InsightBundleResponse) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("生成于 ${bundle.generatedAt}", style = MaterialTheme.typography.labelLarge)
            bundle.items.forEach { item ->
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text("${if (item.epistemicStatus == "fact") "事实" else "推断"} · 置信度 ${(item.confidence * 100).toInt()}%")
                Text(item.explanation)
                item.evidence.forEach { evidence ->
                    Text("依据：${evidence.excerpt} (${(evidence.confidence * 100).toInt()}%)")
                }
                item.suggestedAction?.let { Text("建议行动：$it") }
                item.suggestedAt?.let { Text("建议时间：$it") }
            }
        }
    }
}

private val MEMORY_TYPES = listOf(
    null to "全部类型",
    "event" to "事件",
    "preference" to "偏好",
    "relationship" to "关系",
    "commitment" to "承诺",
    "profile" to "资料",
    "other" to "其他",
)

private val MemoryTimeFilter.label: String
    get() = when (this) {
        MemoryTimeFilter.ALL -> "全部时间"
        MemoryTimeFilter.LAST_30_DAYS -> "近 30 天"
        MemoryTimeFilter.LAST_YEAR -> "近一年"
    }
