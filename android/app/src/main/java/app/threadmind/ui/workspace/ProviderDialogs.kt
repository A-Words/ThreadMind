package app.threadmind

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import app.threadmind.domain.withDeviceDefaults
import app.threadmind.network.MemoryRecordResponse
import app.threadmind.network.InsightBundleResponse
import app.threadmind.provider.ProviderPreflightResult
import app.threadmind.provider.ProviderTarget
import app.threadmind.ui.theme.ThreadMindTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.ZoneId

@Composable
internal fun ProviderReviewDialog(
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
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
internal fun ProviderTargetDialog(
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
