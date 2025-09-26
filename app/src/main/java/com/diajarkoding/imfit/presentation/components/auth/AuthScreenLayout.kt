package com.diajarkoding.imfit.presentation.components.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diajarkoding.imfit.MainViewModel
import com.diajarkoding.imfit.presentation.components.GlobalLoadingIndicator
import com.diajarkoding.imfit.presentation.ui.main.activityViewModel
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun activityViewModel(): MainViewModel {
    val context = LocalContext.current
    return viewModel(context as ComponentActivity)
}

@Composable
fun AuthScreenLayout(
    title: String,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = activityViewModel(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = IMFITSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
//        Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(IMFITSpacing.xl))
            content()
        }

        if (state.isGlobalLoading) {
            GlobalLoadingIndicator()
        }
    }
}