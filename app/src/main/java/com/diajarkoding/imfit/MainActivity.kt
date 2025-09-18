package com.diajarkoding.imfit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diajarkoding.imfit.presentation.navigation.AuthNavigation
import com.diajarkoding.imfit.presentation.ui.theme.IMFITTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint // Jangan lupa anotasi Hilt
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IMFITTheme {
                // Panggil Navigasi di sini
                AuthNavigation()
            }
        }
    }
}