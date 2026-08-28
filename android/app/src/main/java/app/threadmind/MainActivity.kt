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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.threadmind.auth.AuthStep
import app.threadmind.auth.AuthViewModel
import app.threadmind.domain.ActionCard
import app.threadmind.domain.ActionStatus
import app.threadmind.domain.ActionType
import app.threadmind.network.MemoryRecordResponse
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
        setContent { MaterialTheme { ThreadMindRoot(authViewModel, viewModel) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        viewModel.importImage(uri)
    }
}

@Composable
private fun ThreadMindRoot(authViewModel: AuthViewModel, mainViewModel: MainViewModel) {
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    if (authState.step == AuthStep.AUTHENTICATED) {
        ThreadMindScreen(mainViewModel, onSignOut = authViewModel::signOut)
    } else {
        AuthScreen(authViewModel)
    }
}

@Composable
private fun AuthScreen(viewModel: AuthViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ThreadMind", style = MaterialTheme.typography.headlineLarge)
        Text("使用邮箱验证码登录，不依赖 Google 服务或浏览器跳转。")
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::setEmail,
            enabled = state.step == AuthStep.EMAIL && !state.isLoading,
            label = { Text("邮箱") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.step == AuthStep.OTP) {
            OutlinedTextField(
                value = state.token,
                onValueChange = viewModel::setToken,
                label = { Text("六位验证码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::verifyCode, enabled = !state.isLoading) { Text("验证并登录") }
                TextButton(onClick = viewModel::editEmail, enabled = !state.isLoading) { Text("更换邮箱") }
            }
        } else {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = state.privacyAccepted,
                    onCheckedChange = viewModel::setPrivacyAccepted,
                    enabled = !state.isLoading,
                )
                Text("我已阅读并同意隐私与数据处理说明")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::requestLoginCode, enabled = !state.isLoading) { Text("登录") }
                Button(onClick = viewModel::requestRegistrationCode, enabled = !state.isLoading) { Text("注册") }
            }
        }
        if (state.isLoading) CircularProgressIndicator()
        state.message?.let { Text(it) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ThreadMindScreen(viewModel: MainViewModel, onSignOut: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingCardId by remember { mutableStateOf<String?>(null) }
    var memoryToDelete by remember { mutableStateOf<MemoryRecordResponse?>(null) }
    LaunchedEffect(Unit) { viewModel.checkBackend() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), viewModel::importImage)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val cardId = pendingCardId
        pendingCardId = null
        if (cardId != null && grants.values.all { it }) scope.launch { viewModel.execute(cardId) }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("ThreadMind") },
            actions = { TextButton(onClick = onSignOut) { Text("退出") } },
        )
    }) { padding ->
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(card.fields["title"] ?: card.fields["displayName"] ?: card.type.name, style = MaterialTheme.typography.titleMedium)
                        Text("版本 ${card.version} · ${card.status}")
                        card.evidence.forEach { Text("依据：${it.excerpt} (${(it.confidence * 100).toInt()}%)") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.confirm(card.id) }, enabled = card.status == ActionStatus.READY) { Text("确认当前版本") }
                            Button(
                                onClick = {
                                    pendingCardId = card.id
                                    permissions.launch(
                                        if (card.type == ActionType.CREATE_MEETING) arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                                        else arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
                                    )
                                },
                                enabled = card.status == ActionStatus.CONFIRMED,
                            ) { Text("授权并写入系统") }
                        }
                    }
                }
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
