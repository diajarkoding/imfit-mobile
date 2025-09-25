package com.diajarkoding.imfit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.diajarkoding.imfit.presentation.navigation.RootNavigation
import com.diajarkoding.imfit.theme.IMFITTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installSplashScreen().setKeepOnScreenCondition {
            // Splash screen akan terus tampil selama startDestination masih null
            viewModel.state.value.startDestination == null
        }

        setContent {
            IMFITTheme {
                val startDestination by viewModel.state.collectAsState()

                // Tampilkan RootNavigation hanya jika startDestination sudah ditentukan
                startDestination.startDestination?.let { route ->
                    RootNavigation(
                        startDestination = route,
                        showLoading = { viewModel.showGlobalLoading() },
                        hideLoading = { viewModel.hideGlobalLoading() },
                    )
                }
            }
        }
    }
}