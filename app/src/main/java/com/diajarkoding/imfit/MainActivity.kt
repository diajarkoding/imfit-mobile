package com.diajarkoding.imfit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.diajarkoding.imfit.presentation.navigation.AuthNavigation
import com.diajarkoding.imfit.presentation.ui.theme.IMFITTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint // Jangan lupa anotasi Hilt
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IMFITTheme {
                // Panggil Navigasi di sini
                AuthNavigation()
            }
        }
    }
}