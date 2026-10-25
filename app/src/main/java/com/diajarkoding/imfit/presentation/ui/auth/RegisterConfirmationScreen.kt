package com.diajarkoding.imfit.presentation.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun RegisterConfirmationScreen(onNavigateToLogin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(IMFITSpacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.register_confirmation_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            modifier = Modifier.padding(top = IMFITSpacing.md),
            text = stringResource(R.string.register_confirmation_message),
            style = MaterialTheme.typography.bodyLarge,
        )
        IMFITButton(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = IMFITSpacing.xl),
            text = stringResource(R.string.register_confirmation_login),
            onClick = onNavigateToLogin,
        )
    }
}
