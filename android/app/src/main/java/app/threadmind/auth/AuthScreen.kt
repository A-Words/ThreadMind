package app.threadmind.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.threadmind.ui.theme.ThreadMindSpacing
import app.threadmind.ui.theme.ThreadMindTheme

data class AuthActions(
    val onEmailChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onConfirmPasswordChange: (String) -> Unit,
    val onTokenChange: (String) -> Unit,
    val onPrivacyAcceptedChange: (Boolean) -> Unit,
    val onIntentChange: (AuthIntent) -> Unit,
    val onMethodChange: (AuthMethod) -> Unit,
    val onSubmitCredentials: () -> Unit,
    val onStartPasswordRecovery: () -> Unit,
    val onVerifyCode: () -> Unit,
    val onResendCode: () -> Unit,
    val onUpdatePassword: () -> Unit,
    val onCancelFlow: () -> Unit,
    val onClearFeedback: () -> Unit,
) {
    companion object {
        val None = AuthActions(
            onEmailChange = {},
            onPasswordChange = {},
            onConfirmPasswordChange = {},
            onTokenChange = {},
            onPrivacyAcceptedChange = {},
            onIntentChange = {},
            onMethodChange = {},
            onSubmitCredentials = {},
            onStartPasswordRecovery = {},
            onVerifyCode = {},
            onResendCode = {},
            onUpdatePassword = {},
            onCancelFlow = {},
            onClearFeedback = {},
        )
    }
}

object AuthTestTags {
    const val EMAIL = "auth_email"
    const val PASSWORD = "auth_password"
    const val CONFIRM_PASSWORD = "auth_confirm_password"
    const val TOKEN = "auth_token"
    const val PRIMARY_ACTION = "auth_primary_action"
    const val RESEND_ACTION = "auth_resend_action"
    const val PRIVACY_CONSENT = "auth_privacy_consent"
}

@Composable
fun AuthScreen(
    state: AuthUiState,
    actions: AuthActions,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showPrivacyDetails by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.feedback) {
        val feedback = state.feedback ?: return@LaunchedEffect
        if (feedback.kind != AuthFeedbackKind.ERROR) {
            snackbarHostState.showSnackbar(feedback.message)
            actions.onClearFeedback()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = ThreadMindSpacing.large, vertical = ThreadMindSpacing.medium),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ThreadMindSpacing.medium),
            ) {
                when (state.step) {
                    AuthStep.CREDENTIALS -> CredentialsPage(
                        state = state,
                        actions = actions,
                        onShowPrivacyDetails = { showPrivacyDetails = true },
                    )
                    AuthStep.CODE -> CodePage(state, actions)
                    AuthStep.NEW_PASSWORD -> NewPasswordPage(state, actions)
                    AuthStep.AUTHENTICATED -> Unit
                }
            }
        }
    }

    if (showPrivacyDetails) {
        PrivacyDetailsSheet(onDismiss = { showPrivacyDetails = false })
    }
}

@Composable
private fun CredentialsPage(
    state: AuthUiState,
    actions: AuthActions,
    onShowPrivacyDetails: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmPasswordFocus = remember { FocusRequester() }
    val privacyFocus = remember { FocusRequester() }
    val isRegistration = state.intent == AuthIntent.REGISTER
    val usesPassword = state.method == AuthMethod.PASSWORD

    LaunchedEffect(state.fieldErrors) {
        when {
            state.fieldErrors.email != null -> emailFocus.requestFocus()
            state.fieldErrors.password != null -> passwordFocus.requestFocus()
            state.fieldErrors.confirmPassword != null -> confirmPasswordFocus.requestFocus()
            state.fieldErrors.privacy != null -> privacyFocus.requestFocus()
        }
    }

    Text(
        text = "ThreadMind",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleLarge,
    )
    Column(verticalArrangement = Arrangement.spacedBy(ThreadMindSpacing.small)) {
        Text(
            text = if (isRegistration) "创建账号" else "欢迎回来",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = if (isRegistration) {
                "开始把聊天变成可确认的行动"
            } else {
                "继续整理聊天中的行动与记忆"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }

    ErrorFeedback(state.feedback)

    OutlinedTextField(
        value = state.email,
        onValueChange = actions.onEmailChange,
        enabled = !state.isLoading,
        label = { Text("邮箱") },
        placeholder = { Text("name@example.com") },
        isError = state.fieldErrors.email != null,
        supportingText = state.fieldErrors.email?.let { error -> { FieldError(error) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = if (usesPassword) ImeAction.Next else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onNext = { passwordFocus.requestFocus() },
            onDone = { focusManager.clearFocus(); actions.onSubmitCredentials() },
        ),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(emailFocus)
            .semantics { contentType = ContentType.EmailAddress }
            .testTag(AuthTestTags.EMAIL),
    )

    if (usesPassword) {
        PasswordField(
            value = state.password,
            onValueChange = actions.onPasswordChange,
            label = "密码",
            enabled = !state.isLoading,
            error = state.fieldErrors.password,
            contentType = if (isRegistration) ContentType.NewPassword else ContentType.Password,
            imeAction = if (isRegistration) ImeAction.Next else ImeAction.Done,
            onImeAction = {
                if (isRegistration) confirmPasswordFocus.requestFocus()
                else { focusManager.clearFocus(); actions.onSubmitCredentials() }
            },
            modifier = Modifier.focusRequester(passwordFocus),
            testTag = AuthTestTags.PASSWORD,
        )
        if (!isRegistration) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = actions.onStartPasswordRecovery,
                    enabled = !state.isLoading,
                ) { Text("忘记密码？") }
            }
        }
    }

    if (isRegistration && usesPassword) {
        PasswordField(
            value = state.confirmPassword,
            onValueChange = actions.onConfirmPasswordChange,
            label = "确认密码",
            enabled = !state.isLoading,
            error = state.fieldErrors.confirmPassword,
            contentType = ContentType.NewPassword,
            imeAction = ImeAction.Done,
            onImeAction = { focusManager.clearFocus(); actions.onSubmitCredentials() },
            modifier = Modifier.focusRequester(confirmPasswordFocus),
            testTag = AuthTestTags.CONFIRM_PASSWORD,
        )
        PasswordRequirements(state.password, state.confirmPassword)
    }

    if (isRegistration) {
        PrivacyConsent(
            checked = state.privacyAccepted,
            enabled = !state.isLoading,
            error = state.fieldErrors.privacy,
            onCheckedChange = actions.onPrivacyAcceptedChange,
            onShowDetails = onShowPrivacyDetails,
            modifier = Modifier.focusRequester(privacyFocus),
        )
    }

    PrimaryActionButton(
        label = when {
            !usesPassword -> "发送验证码"
            isRegistration -> "创建账号"
            else -> "登录"
        },
        isLoading = state.isLoading,
        onClick = actions.onSubmitCredentials,
    )

    OutlinedButton(
        onClick = {
            actions.onMethodChange(if (usesPassword) AuthMethod.OTP else AuthMethod.PASSWORD)
        },
        enabled = !state.isLoading,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text(
            when {
                usesPassword && isRegistration -> "使用邮箱验证码注册"
                usesPassword -> "使用邮箱验证码登录"
                isRegistration -> "使用密码注册"
                else -> "使用密码登录"
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (isRegistration) "已有账号？" else "还没有账号？")
        TextButton(
            onClick = {
                actions.onIntentChange(if (isRegistration) AuthIntent.LOGIN else AuthIntent.REGISTER)
            },
            enabled = !state.isLoading,
        ) {
            Text(if (isRegistration) "返回登录" else "创建账号")
        }
    }
}

@Composable
private fun CodePage(state: AuthUiState, actions: AuthActions) {
    val focusManager = LocalFocusManager.current
    val tokenFocus = remember { FocusRequester() }
    val isBusy = state.isLoading || state.isResendingCode
    val isCodeComplete = state.token.length == 6
    LaunchedEffect(state.codePurpose) {
        tokenFocus.requestFocus()
    }
    LaunchedEffect(state.fieldErrors.token) {
        if (state.fieldErrors.token != null) tokenFocus.requestFocus()
    }

    IconButton(onClick = actions.onCancelFlow, enabled = !isBusy) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
    }
    Column(verticalArrangement = Arrangement.spacedBy(ThreadMindSpacing.small)) {
        Text(
            text = codeTitle(state.codePurpose),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text("六位验证码已发送至", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.email, style = MaterialTheme.typography.titleMedium)
        if (state.codePurpose != AuthCodePurpose.ACCOUNT_PASSWORD_UPDATE) {
            TextButton(onClick = actions.onCancelFlow, enabled = !isBusy) {
                Text("更换邮箱")
            }
        }
    }

    state.codeDeliveryNotice?.let { CodeDeliveryNotice(it) }
    ErrorFeedback(state.feedback)

    OutlinedTextField(
        value = state.token,
        onValueChange = actions.onTokenChange,
        enabled = !isBusy,
        label = { Text("六位验证码") },
        isError = state.fieldErrors.token != null,
        supportingText = {
            val error = state.fieldErrors.token
            if (error != null) FieldError(error) else Text("仅输入邮件中的六位数字")
        },
        suffix = { Text("${state.token.length}/6") },
        textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 6.sp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { focusManager.clearFocus(); actions.onVerifyCode() },
        ),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(tokenFocus)
            .testTag(AuthTestTags.TOKEN),
    )

    PrimaryActionButton(
        label = "验证并继续",
        isLoading = state.isLoading,
        enabled = isCodeComplete && !state.isResendingCode,
        onClick = actions.onVerifyCode,
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(
            onClick = actions.onResendCode,
            enabled = !state.isLoading && !state.isResendingCode && state.resendSecondsRemaining == 0,
            modifier = Modifier.testTag(AuthTestTags.RESEND_ACTION),
        ) {
            if (state.isResendingCode) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(ThreadMindSpacing.small))
                Text("正在重新发送")
            } else {
                Text(resendLabel(state.resendSecondsRemaining))
            }
        }
    }
}

@Composable
private fun NewPasswordPage(state: AuthUiState, actions: AuthActions) {
    val focusManager = LocalFocusManager.current
    val passwordFocus = remember { FocusRequester() }
    val confirmPasswordFocus = remember { FocusRequester() }

    LaunchedEffect(state.fieldErrors) {
        when {
            state.fieldErrors.password != null -> passwordFocus.requestFocus()
            state.fieldErrors.confirmPassword != null -> confirmPasswordFocus.requestFocus()
        }
    }

    IconButton(onClick = actions.onCancelFlow, enabled = !state.isLoading) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
    }
    Column(verticalArrangement = Arrangement.spacedBy(ThreadMindSpacing.small)) {
        Text(
            text = if (state.codePurpose == AuthCodePurpose.ACCOUNT_PASSWORD_UPDATE) "设置或更新密码" else "设置新密码",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text("请设置一个至少八位的新密码。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    ErrorFeedback(state.feedback)

    PasswordField(
        value = state.password,
        onValueChange = actions.onPasswordChange,
        label = "新密码",
        enabled = !state.isLoading,
        error = state.fieldErrors.password,
        contentType = ContentType.NewPassword,
        imeAction = ImeAction.Next,
        onImeAction = { confirmPasswordFocus.requestFocus() },
        modifier = Modifier.focusRequester(passwordFocus),
        testTag = AuthTestTags.PASSWORD,
    )
    PasswordField(
        value = state.confirmPassword,
        onValueChange = actions.onConfirmPasswordChange,
        label = "确认新密码",
        enabled = !state.isLoading,
        error = state.fieldErrors.confirmPassword,
        contentType = ContentType.NewPassword,
        imeAction = ImeAction.Done,
        onImeAction = { focusManager.clearFocus(); actions.onUpdatePassword() },
        modifier = Modifier.focusRequester(confirmPasswordFocus),
        testTag = AuthTestTags.CONFIRM_PASSWORD,
    )
    PasswordRequirements(state.password, state.confirmPassword)
    PrimaryActionButton(
        label = "保存新密码",
        isLoading = state.isLoading,
        onClick = actions.onUpdatePassword,
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    error: String?,
    contentType: ContentType,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { message -> { FieldError(message) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction() },
            onDone = { onImeAction() },
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏密码" else "显示密码",
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.contentType = contentType }
            .testTag(testTag),
    )
}

@Composable
private fun PasswordRequirements(password: String, confirmation: String) {
    Column(verticalArrangement = Arrangement.spacedBy(ThreadMindSpacing.xSmall)) {
        PasswordRequirement("至少八位", password.length >= 8)
        PasswordRequirement("两次输入一致", confirmation.isNotEmpty() && password == confirmation)
        Text(
            "其他字符组合和泄露密码拦截以服务端策略为准。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PasswordRequirement(label: String, satisfied: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ThreadMindSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {
            stateDescription = if (satisfied) "已满足" else "未满足"
        },
    ) {
        Icon(
            imageVector = if (satisfied) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (satisfied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            color = if (satisfied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PrivacyConsent(
    checked: Boolean,
    enabled: Boolean,
    error: String?,
    onCheckedChange: (Boolean) -> Unit,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ThreadMindSpacing.xSmall)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                )
                .focusTarget()
                .testTag(AuthTestTags.PRIVACY_CONSENT)
                .semantics { stateDescription = if (checked) "已同意" else "未同意" },
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            Text("我已阅读并同意", modifier = Modifier.weight(1f))
            TextButton(onClick = onShowDetails, enabled = enabled) {
                Text("隐私与数据处理说明")
            }
        }
        error?.let { FieldError(it) }
    }
}

@Composable
private fun PrimaryActionButton(
    label: String,
    isLoading: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag(AuthTestTags.PRIMARY_ACTION),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(ThreadMindSpacing.small))
        }
        Text(label)
    }
}

@Composable
private fun CodeDeliveryNotice(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(message, modifier = Modifier.padding(ThreadMindSpacing.medium))
    }
}

private fun resendLabel(secondsRemaining: Int): String {
    if (secondsRemaining <= 0) return "重新发送验证码"
    val minutes = secondsRemaining / 60
    val seconds = secondsRemaining % 60
    return "%02d:%02d 后可重新发送".format(minutes, seconds)
}

@Composable
private fun ErrorFeedback(feedback: AuthFeedback?) {
    if (feedback?.kind != AuthFeedbackKind.ERROR) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(feedback.message, modifier = Modifier.padding(ThreadMindSpacing.medium))
    }
}

@Composable
private fun FieldError(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PrivacyDetailsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ThreadMindSpacing.large)
                .padding(bottom = ThreadMindSpacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(ThreadMindSpacing.medium),
        ) {
            Text(
                "隐私与数据处理说明",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            PrivacySection("云端处理", "你主动提交的聊天截图和补充文字会发送到云端服务，用于生成可核对的行动卡、记忆和建议。")
            PrivacySection("操作前确认", "写入联系人、日历等系统数据前，ThreadMind 会展示具体内容并再次请求你的确认和系统权限。")
            PrivacySection("记忆由你控制", "系统保存的记忆会展示来源、置信度和版本；你可以查看、修订或删除。")
            PrivacySection("账户数据删除边界", "删除记忆会使其不再用于后续洞察。删除整个账户还需要完成会话撤销、业务数据和认证数据的清理流程。")
            Text(
                "这是产品级数据说明，用来帮助你理解当前功能，不替代正式隐私政策。关闭说明不会自动勾选同意。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("我知道了")
            }
        }
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(ThreadMindSpacing.xSmall)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun codeTitle(purpose: AuthCodePurpose?) = when (purpose) {
    AuthCodePurpose.SIGNUP_CONFIRMATION -> "确认注册邮箱"
    AuthCodePurpose.PASSWORD_RECOVERY -> "验证身份以找回密码"
    AuthCodePurpose.ACCOUNT_PASSWORD_UPDATE -> "验证身份以更新密码"
    AuthCodePurpose.PASSWORDLESS_REGISTRATION -> "使用验证码创建账号"
    AuthCodePurpose.PASSWORDLESS_LOGIN,
    null -> "使用邮箱验证码登录"
}

@Preview(name = "密码登录", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun LoginPreview() {
    ThreadMindTheme { AuthScreen(AuthUiState(isInitializing = false), AuthActions.None) }
}

@Preview(name = "密码注册 - 深色", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun RegisterDarkPreview() {
    ThreadMindTheme(darkTheme = true) {
        AuthScreen(
            AuthUiState(
                intent = AuthIntent.REGISTER,
                email = "person@example.com",
                password = "new-password",
                confirmPassword = "new-password",
                isInitializing = false,
            ),
            AuthActions.None,
        )
    }
}

@Preview(name = "验证码", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun CodePreview() {
    ThreadMindTheme {
        AuthScreen(
            AuthUiState(
                step = AuthStep.CODE,
                codePurpose = AuthCodePurpose.PASSWORDLESS_LOGIN,
                email = "person@example.com",
                resendSecondsRemaining = 60,
                isInitializing = false,
            ),
            AuthActions.None,
        )
    }
}
