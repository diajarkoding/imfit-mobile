package com.diajarkoding.imfit.presentation.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITTheme

// The catalog renders the same components as the screens, not separate preview copies.
@Composable
private fun AuthComponentStates() {
    val colors = IMFITColors.training
    Column(Modifier.fillMaxWidth().background(colors.background).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Auth · Default / Invalid / Disabled / Submitting", color = colors.text)
        AuthTextField("", {}, stringResource(R.string.label_email), stringResource(R.string.placeholder_email))
        AuthTextField("nama@", {}, stringResource(R.string.label_email), stringResource(R.string.placeholder_email),
            error = stringResource(R.string.error_email_invalid))
        AuthTextField("nama@email.com", {}, stringResource(R.string.label_email), "", enabled = false)
        AuthProfilePhotoField(null, onClick = {})
        AuthPrimaryButton(stringResource(R.string.login_button), {})
        AuthPrimaryButton(stringResource(R.string.login_button), {}, enabled = false)
        AuthPrimaryButton(stringResource(R.string.login_button), {}, loading = true,
            loadingText = stringResource(R.string.auth_logging_in))
        AuthOperationError(stringResource(R.string.auth_network_error), stringResource(R.string.auth_login_failure_title))
        AuthOperationError(stringResource(R.string.auth_registration_failed), stringResource(R.string.auth_register_failure_title))
        AuthOperationError(stringResource(R.string.auth_camera_permission_denied), stringResource(R.string.auth_photo_failure_title))
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
private fun AuthComponentsLight() { IMFITTheme { AuthComponentStates() } }

@Preview(showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
private fun AuthComponentsDark() { IMFITTheme(darkTheme = true) { AuthComponentStates() } }

@Preview(showBackground = true, widthDp = 360, heightDp = 1100, fontScale = 2f)
@Composable
private fun AuthComponentsLargeFont() { IMFITTheme { AuthComponentStates() } }
