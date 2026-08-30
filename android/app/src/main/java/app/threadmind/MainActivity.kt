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
import app.threadmind.network.MemoryRecordResponse
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
    var pendingCardId by remember { mutableStateOf<String?>(null) }
    var memoryToDelete by remember { mutableStateOf<MemoryRecordResponse?>(null) }
    LaunchedEffect(Unit) { viewModel.checkBackend() }
    LaunchedEffect(authFeedback) {
        val feedback = authFeedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(feedback.message)
        onClearAuthFeedback()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), viewModel::importImage)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val cardId = pendingCardId
        pendingCardId = null
        if (cardId != null) {
            if (grants.values.all { it }) scope.launch { viewModel.execute(cardId) }
            else viewModel.permissionDenied(cardId)
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
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("记忆中心", style = MaterialTheme.typography.headlineSmall)
                    TextButton(
                        onClick = viewModel::checkBackend,
                        enabled = state.backendStatus != BackendStatus.CHECKING,
                    ) { Text("刷新") }
                }
                Text("你可以核对、修订或删除系统保存的记忆。修订会保留来源和历史版本。")
                if (state.backendStatus == BackendStatus.CHECKING) CircularProgressIndicator()
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
                    onSave = { fields, target, issues -> viewModel.editCard(card.id, fields, target, issues) },
                    onConfirm = { viewModel.confirm(card.id) },
                    onCancel = { viewModel.cancelCard(card.id) },
                    onExecute = {
                        pendingCardId = card.id
                        permissions.launch(
                            if (card.type == ActionType.CREATE_MEETING) arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                            else arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
                        )
                    },
                    onRetryReceipt = { viewModel.retryReceipt(card.id) },
                )
            }
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
}

@Composable
private fun ActionCardReviewCard(
    card: ActionCard,
    isPending: Boolean,
    receiptPending: Boolean,
    onSave: (Map<String, String>, String, Set<String>) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
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
            fields.toSortedMap().forEach { (field, value) ->
                val confidence = card.fieldConfidence[field]
                OutlinedTextField(
                    value = value,
                    onValueChange = { updated -> fields = fields + (field to updated) },
                    enabled = editable && !isPending,
                    label = {
                        Text(
                            buildString {
                                append(field)
                                if (confidence != null) append(" · ${(confidence * 100).toInt()}%${if (confidence < 0.8) " 低置信" else ""}")
                            },
                        )
                    },
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
            val changed = fields != card.fields || targetAccountId != card.targetAccountId.orEmpty() || resolvedIssues.isNotEmpty()
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSave(fields, targetAccountId.trim(), resolvedIssues) },
                        enabled = !isPending && targetAccountId.isNotBlank() && changed,
                    ) { Text("保存修改") }
                    Button(onClick = onConfirm, enabled = !isPending && card.status == ActionStatus.READY && !changed) {
                        Text("确认当前版本")
                    }
                }
                TextButton(onClick = onCancel, enabled = !isPending) { Text("取消这张卡片") }
            }
            Button(onClick = onExecute, enabled = !isPending && card.status == ActionStatus.CONFIRMED) {
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
            Text("来源：${memory.sourceRefs.joinToString().ifBlank { "无" }}")
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
