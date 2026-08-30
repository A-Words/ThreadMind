package app.threadmind.auth

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.runtime.mutableStateOf
import app.threadmind.ui.theme.ThreadMindTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun passwordLoginIsTheDefaultSingleTaskScreen() {
        setAuthContent(AuthUiState(isInitializing = false))

        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        compose.onNodeWithText("登录").assertIsDisplayed()
        compose.onNodeWithText("使用邮箱验证码登录").assertIsDisplayed()
        compose.onNodeWithText("还没有账号？").assertIsDisplayed()
        compose.onAllNodesWithText("我已阅读并同意").assertCountEquals(0)
    }

    @Test fun registrationShowsRequirementsAndPrivacyBottomSheetWithoutImplicitConsent() {
        var accepted = false
        setAuthContent(
            state = AuthUiState(intent = AuthIntent.REGISTER, isInitializing = false),
            actions = AuthActions.None.copy(onPrivacyAcceptedChange = { accepted = it }),
        )

        compose.onNodeWithTag(AuthTestTags.PRIMARY_ACTION).assertIsDisplayed()
        compose.onNodeWithText("至少八位").assertIsDisplayed()
        compose.onNodeWithText("使用邮箱验证码注册").assertIsDisplayed()
        compose.onNodeWithText("隐私与数据处理说明").performClick()
        compose.onNodeWithText("云端处理").assertIsDisplayed()
        assertTrue(!accepted)
    }

    @Test fun fieldErrorsStayBesideTheirInputs() {
        setAuthContent(
            AuthUiState(
                intent = AuthIntent.REGISTER,
                fieldErrors = AuthFieldErrors(
                    email = "请输入有效的邮箱地址。",
                    password = "密码至少需要八位。",
                    confirmPassword = "两次输入的密码不一致。",
                    privacy = "注册前请先同意隐私与数据处理说明。",
                ),
                isInitializing = false,
            ),
        )

        compose.onNodeWithText("请输入有效的邮箱地址。").assertIsDisplayed()
        compose.onNodeWithText("密码至少需要八位。").assertIsDisplayed()
        compose.onNodeWithText("两次输入的密码不一致。").assertIsDisplayed()
        compose.onNodeWithText("注册前请先同意隐私与数据处理说明。").assertIsDisplayed()
    }

    @Test fun verificationResendReflectsCountdownAndLoadingState() {
        val state = mutableStateOf(
            AuthUiState(
                step = AuthStep.CODE,
                codePurpose = AuthCodePurpose.PASSWORDLESS_LOGIN,
                email = "person@example.com",
                codeDeliveryNotice = "验证码已发送，请检查邮箱。",
                resendSecondsRemaining = 60,
                isInitializing = false,
            ),
        )
        compose.setContent { ThreadMindTheme { AuthScreen(state.value, AuthActions.None) } }

        compose.onNodeWithText("验证码已发送，请检查邮箱。").assertIsDisplayed()
        compose.onNodeWithText("验证并继续").assertIsNotEnabled()
        compose.onNodeWithText("01:00 后可重新发送").assertIsNotEnabled()

        compose.runOnIdle { state.value = state.value.copy(token = "123456") }
        compose.onNodeWithText("6/6").assertIsDisplayed()
        compose.onNodeWithText("验证并继续").assertIsEnabled()

        compose.runOnIdle {
            state.value = AuthUiState(
                step = AuthStep.CODE,
                codePurpose = AuthCodePurpose.PASSWORD_RECOVERY,
                email = "person@example.com",
                resendSecondsRemaining = 0,
                isLoading = true,
                isInitializing = false,
            )
        }
        compose.onNodeWithText("验证并继续").assertIsNotEnabled()
        compose.onNodeWithText("重新发送验证码").assertIsNotEnabled()
    }

    @Test fun resendUsesItsOwnProgressStateAndAccountEmailCannotBeChanged() {
        setAuthContent(
            AuthUiState(
                step = AuthStep.CODE,
                codePurpose = AuthCodePurpose.ACCOUNT_PASSWORD_UPDATE,
                email = "person@example.com",
                token = "123456",
                isResendingCode = true,
                isInitializing = false,
            ),
        )

        compose.onNodeWithText("正在重新发送").assertIsDisplayed()
        compose.onNodeWithText("验证并继续").assertIsNotEnabled()
        compose.onAllNodesWithText("更换邮箱").assertCountEquals(0)
    }

    @Test fun newPasswordKeyboardDoneSubmits() {
        var submitted = false
        setAuthContent(
            state = AuthUiState(
                step = AuthStep.NEW_PASSWORD,
                codePurpose = AuthCodePurpose.PASSWORD_RECOVERY,
                password = "new-password",
                confirmPassword = "new-password",
                isInitializing = false,
            ),
            actions = AuthActions.None.copy(onUpdatePassword = { submitted = true }),
        )

        compose.onNodeWithTag(AuthTestTags.CONFIRM_PASSWORD).performImeAction()
        compose.runOnIdle { assertTrue(submitted) }
    }

    private fun setAuthContent(
        state: AuthUiState,
        actions: AuthActions = AuthActions.None,
    ) {
        compose.setContent { ThreadMindTheme { AuthScreen(state, actions) } }
    }
}
