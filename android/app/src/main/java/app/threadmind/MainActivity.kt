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
import app.threadmind.domain.withDeviceDefaults
import app.threadmind.network.MemoryRecordResponse
import app.threadmind.network.InsightBundleResponse
import app.threadmind.provider.ProviderPreflightResult
import app.threadmind.provider.ProviderTarget
import app.threadmind.ui.theme.ThreadMindTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.ZoneId

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
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
    val accountId = authState.session?.userId
    LaunchedEffect(accountId) { mainViewModel.switchAccount(accountId) }
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
        androidx.compose.runtime.key(accountId) {
        if (mainState.accountId == accountId) WorkspaceRoute(
            mainViewModel,
            isAuthLoading = authState.isLoading,
            authFeedback = authState.feedback,
            onClearAuthFeedback = authViewModel::clearFeedback,
            onManageAccount = authViewModel::beginPasswordUpdate,
            onSignOut = authViewModel::signOut,
        )
        }
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
