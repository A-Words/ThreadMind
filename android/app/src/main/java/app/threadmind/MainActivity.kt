package app.threadmind

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.threadmind.auth.AuthActions
import app.threadmind.auth.AuthScreen
import app.threadmind.auth.AuthStep
import app.threadmind.auth.AuthViewModel
import app.threadmind.ui.theme.ThreadMindTheme
import dagger.hilt.android.AndroidEntryPoint

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
