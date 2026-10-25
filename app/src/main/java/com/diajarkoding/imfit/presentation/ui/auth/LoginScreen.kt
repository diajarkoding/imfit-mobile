package com.diajarkoding.imfit.presentation.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.BuildConfig
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.*

@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    onLoginSuccess: () -> Unit,
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit,
    isIndonesian: Boolean = true,
    onToggleLanguage: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.loginSuccess) { if (state.loginSuccess) onLoginSuccess() }
    val error = state.errorMessage?.let { stringResource(it) }
    LoginScreenContent(state.email, state.password,
        state.emailError?.let { stringResource(it) }, state.passwordError?.let { stringResource(it) },
        state.isLoading, isDarkMode, isIndonesian,
        viewModel::onEmailChange, viewModel::onPasswordChange, viewModel::login,
        onNavigateToRegister, onToggleTheme, onToggleLanguage, snackbar, error)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoginScreenContent(
    email: String = "", password: String = "", emailError: String? = null, passwordError: String? = null,
    isLoading: Boolean = false, isDarkMode: Boolean = false, isIndonesian: Boolean = true,
    onEmailChange: (String) -> Unit = {}, onPasswordChange: (String) -> Unit = {},
    onLogin: () -> Unit = {}, onNavigateToRegister: () -> Unit = {},
    onToggleTheme: () -> Unit = {}, onToggleLanguage: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    operationError: String? = null
) {
    val colors = IMFITColors.training
    val keyboardVisible = WindowInsets.isImeVisible
    Box(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            AuthPreferences(isDarkMode, isIndonesian, onToggleTheme, onToggleLanguage)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 40.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                AuthBrandMark()
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.login_welcome_title), color = colors.text,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 35.sp, letterSpacing = (-0.6).sp),
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.login_welcome_subtitle), color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 280.dp))
                Spacer(Modifier.height(36.dp))
                AuthTextField(email, onEmailChange, stringResource(R.string.label_email), stringResource(R.string.placeholder_email),
                    error = emailError, keyboardType = KeyboardType.Email, enabled = !isLoading)
                Spacer(Modifier.height(20.dp))
                AuthTextField(password, onPasswordChange, stringResource(R.string.label_password), stringResource(R.string.placeholder_password),
                    error = passwordError, password = true, imeAction = ImeAction.Done, enabled = !isLoading, onDone = onLogin)
                Spacer(Modifier.height(20.dp))
                if (operationError != null) {
                    AuthOperationError(operationError, stringResource(R.string.auth_login_failure_title))
                    Spacer(Modifier.height(12.dp))
                }
                AuthPrimaryButton(stringResource(R.string.login_button), onLogin, isLoading,
                    loadingText = stringResource(R.string.auth_logging_in))
                Spacer(Modifier.height(8.dp))
                AuthRedirect(stringResource(R.string.login_redirect_prompt), stringResource(R.string.login_redirect_action), onNavigateToRegister, enabled = !isLoading)
            }
            if (!keyboardVisible) Text("v${BuildConfig.VERSION_NAME}", color = colors.muted,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 956)
@Composable
private fun LoginPreviewLight() { IMFITTheme { LoginScreenContent() } }

@Preview(showBackground = true, widthDp = 412, heightDp = 956)
@Composable
private fun LoginPreviewDark() { IMFITTheme(darkTheme = true) { LoginScreenContent(isDarkMode = true) } }

@Preview(showBackground = true, widthDp = 412, heightDp = 956)
@Composable
private fun LoginPreviewValidation() {
    IMFITTheme { LoginScreenContent(emailError = "Email harus diisi.", passwordError = "Kata sandi harus diisi.") }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 956)
@Composable
private fun LoginPreviewFailure() {
    IMFITTheme {
        LoginScreenContent(email = "nama@email.com", operationError = stringResource(R.string.auth_network_error))
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 956)
@Composable
private fun LoginPreviewFailureDark() {
    IMFITTheme(darkTheme = true) {
        LoginScreenContent(email = "nama@email.com", isDarkMode = true,
            operationError = stringResource(R.string.error_invalid_email_password))
    }
}
