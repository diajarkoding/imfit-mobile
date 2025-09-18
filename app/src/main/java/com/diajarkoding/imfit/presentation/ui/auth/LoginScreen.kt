package com.diajarkoding.imfit.presentation.ui.auth

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    viewModel: LoginViewModel = hiltViewModel() // 1. Inject ViewModel
) {
    // 2. Ambil state dari ViewModel
    val state by viewModel.state.collectAsState()

    // 3. Handle navigasi saat login sukses
    LaunchedEffect(key1 = state.loginSuccess) {
        if (state.loginSuccess) {
            onLoginSuccess()
        }
    }

    AuthScreenLayout(title = stringResource(id = R.string.login_title)) {
        // Tampilkan error jika ada
        state.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        AuthTextField(
            value = state.emailOrUsername,
            // 4. Kirim event ke ViewModel saat ada perubahan
            onValueChange = { viewModel.onEvent(LoginEvent.EmailOrUsernameChanged(it)) },
            label = stringResource(id = R.string.login_email_or_username_label),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        PasswordTextField(
            value = state.password,
            onValueChange = { viewModel.onEvent(LoginEvent.PasswordChanged(it)) },
            label = stringResource(id = R.string.label_password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
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

        // 5. Tampilkan loading atau tombol
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
        Spacer(modifier = Modifier.height(32.dp))

    }
}