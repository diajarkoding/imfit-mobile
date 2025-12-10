package com.diajarkoding.imfit.presentation.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.R
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.presentation.components.common.IMFITLanguageSwitch
import com.diajarkoding.imfit.presentation.components.common.IMFITPasswordField
import com.diajarkoding.imfit.presentation.components.common.IMFITTextField
import com.diajarkoding.imfit.presentation.components.common.IMFITThemeSwitch
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight

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
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.loginSuccess) {
        if (state.loginSuccess) {
            onLoginSuccess()
        }
    }

    val errorMessage = state.errorMessage?.let { stringResource(it) }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = IMFITSpacing.screenHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(IMFITSpacing.huge))
            
            // Logo
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(IMFITShapes.Card)
                    .background(
                        Brush.linearGradient(listOf(Primary, PrimaryLight))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = "IMFIT Logo",
                    modifier = Modifier.size(IMFITSizes.iconXxl),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.xxl))

            Text(
                text = stringResource(R.string.login_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.sm))

            Text(
                text = stringResource(R.string.login_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xxxl))

            IMFITTextField(
                value = state.email,
                onValueChange = { viewModel.onEmailChange(it) },
                label = stringResource(R.string.label_email),
                placeholder = stringResource(R.string.placeholder_email),
                error = state.emailError?.let { stringResource(it) },
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.lg))

            IMFITPasswordField(
                value = state.password,
                onValueChange = { viewModel.onPasswordChange(it) },
                label = stringResource(R.string.label_password),
                error = state.passwordError?.let { stringResource(it) },
                imeAction = ImeAction.Done,
                onImeAction = { viewModel.login() }
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xxxl))

            IMFITButton(
                text = stringResource(R.string.login_button),
                onClick = { onLoginSuccess() },
                isLoading = state.isLoading
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xxl))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.login_redirect_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.login_redirect_action),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Primary,
                    modifier = Modifier.clickable { onNavigateToRegister() }
                )
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.xxxl))

            Box(
                modifier = Modifier
                    .clip(IMFITShapes.Chip)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = IMFITSpacing.lg, vertical = IMFITSpacing.md)
            ) {
                Text(
                    text = stringResource(R.string.login_demo_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.height(IMFITSpacing.huge))
        }

        // Language Toggle (Top Left)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(IMFITSpacing.lg)
        ) {
            IMFITLanguageSwitch(
                isIndonesian = isIndonesian,
                onToggle = onToggleLanguage
            )
        }

        // Theme Toggle (Top Right)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(IMFITSpacing.lg)
        ) {
            IMFITThemeSwitch(
                isDarkMode = isDarkMode,
                onToggle = onToggleTheme
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
    }
}
