package com.diajarkoding.imfit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.diajarkoding.imfit.presentation.navigation.NavGraph
import com.diajarkoding.imfit.theme.IMFITTheme
import com.diajarkoding.imfit.theme.LocalIsDarkTheme
import com.diajarkoding.imfit.theme.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val isDarkMode by themeManager.isDarkMode.collectAsState(initial = false)
            val scope = rememberCoroutineScope()

            CompositionLocalProvider(LocalIsDarkTheme provides isDarkMode) {
                IMFITTheme(darkTheme = isDarkMode) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        NavGraph(
                            isDarkMode = isDarkMode,
                            onToggleTheme = {
                                scope.launch {
                                    themeManager.toggleTheme()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
