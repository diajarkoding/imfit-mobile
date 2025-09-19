package com.diajarkoding.imfit.presentation.ui.auth

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.AuthRedirectText
import com.diajarkoding.imfit.presentation.components.AuthScreenLayout
import com.diajarkoding.imfit.presentation.components.AuthTextField
import com.diajarkoding.imfit.presentation.components.PasswordTextField
import com.diajarkoding.imfit.presentation.components.PrimaryButton

@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(key1 = state.snackbarMessage) {
        state.snackbarMessage?.let {
            snackbarHostState.showSnackbar(message = it)
            viewModel.onEvent(LoginEvent.SnackbarDismissed)
        }
    }

    LaunchedEffect(key1 = state.loginSuccess) {
        if (state.loginSuccess) {
            onLoginSuccess()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        AuthScreenLayout(
            title = stringResource(id = R.string.login_title),
            modifier = Modifier.padding(paddingValues)
        ) {
            AuthTextField(
                value = state.emailOrUsername,
                onValueChange = { viewModel.onEvent(LoginEvent.EmailOrUsernameChanged(it)) },
                label = stringResource(id = R.string.login_email_or_username_label),
                modifier = Modifier.fillMaxWidth(),
                isError = state.emailOrUsernameError != null,
                errorMessage = state.emailOrUsernameError
            )
            Spacer(modifier = Modifier.height(16.dp))

            PasswordTextField(
                value = state.password,
                onValueChange = { viewModel.onEvent(LoginEvent.PasswordChanged(it)) },
                label = stringResource(id = R.string.label_password),
                modifier = Modifier.fillMaxWidth(),
                isError = state.passwordError != null,
                errorMessage = state.passwordError
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = state.rememberMe,
                    onCheckedChange = { viewModel.onEvent(LoginEvent.RememberMeChanged(it)) }
                )
                Text(
                    text = stringResource(id = R.string.login_remember_me),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            if (state.isLoading) {
                CircularProgressIndicator()
            } else {
                PrimaryButton(
                    text = stringResource(id = R.string.login_button),
                    onClick = { viewModel.onEvent(LoginEvent.LoginButtonPressed) },
                    enabled = !state.isLoading
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            AuthRedirectText(
                promptText = stringResource(id = R.string.login_redirect_prompt),
                clickableText = stringResource(id = R.string.login_redirect_action),
                onClick = onNavigateToRegister
            )
        }
    }
}