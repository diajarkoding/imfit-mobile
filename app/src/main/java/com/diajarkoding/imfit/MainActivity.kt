package com.diajarkoding.imfit

import android.content.Context
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
import com.diajarkoding.imfit.theme.LocaleManager
import com.diajarkoding.imfit.theme.LocalIsDarkTheme
import com.diajarkoding.imfit.theme.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeManager: ThemeManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.attachBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize LocaleManager
        LocaleManager.init(this)

        setContent {
            val isDarkMode by themeManager.isDarkMode.collectAsState(initial = false)
            // Observe language state - this triggers recomposition when language changes
            val isIndonesian = LocaleManager.isIndonesian
            // Also observe configVersion to ensure recomposition happens
            // This is read but not used as key - just reading it creates a dependency
            @Suppress("UNUSED_VARIABLE")
            val configVersion = LocaleManager.configurationVersion
            val scope = rememberCoroutineScope()

            CompositionLocalProvider(LocalIsDarkTheme provides isDarkMode) {
                IMFITTheme(darkTheme = isDarkMode) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        // Don't use key() here as it resets navigation to splash screen
                        // Instead rely on state observation for recomposition
                        NavGraph(
                            isDarkMode = isDarkMode,
                            onToggleTheme = {
                                scope.launch {
                                    themeManager.toggleTheme()
                                }
                            },
                            isIndonesian = isIndonesian,
                            onToggleLanguage = {
                                LocaleManager.toggleLanguage(this@MainActivity)
                            }
                        )
                    }
                }
            }
        }
    }
}

