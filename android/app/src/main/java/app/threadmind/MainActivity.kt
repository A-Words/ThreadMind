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
import androidx.compose.runtime.Composable
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
}
