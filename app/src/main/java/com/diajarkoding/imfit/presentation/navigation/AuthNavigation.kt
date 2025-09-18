package com.diajarkoding.imfit.presentation.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.diajarkoding.imfit.presentation.ui.auth.LoginScreen
import com.diajarkoding.imfit.presentation.ui.auth.RegisterScreen
import com.diajarkoding.imfit.presentation.ui.auth.SplashScreen

// Mendefinisikan rute sebagai object agar aman dari typo
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home" // Rute untuk halaman utama setelah login
}

@Composable
fun AuthNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onNavigateToLogin = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.LOGIN) {
            // --- BAGIAN YANG DIPERBAIKI ---
            LoginScreen(
                onNavigateToRegister = {
                    navController.navigate(Routes.REGISTER)
                },
                // Tambahkan aksi untuk navigasi saat login berhasil
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        // Hapus semua halaman auth dari back stack
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.REGISTER) {
            RegisterScreen(
                onNavigateToLogin = {
                    navController.navigateUp()
                },
                // Aksi setelah registrasi sukses
                onRegisterSuccess = {
                    navController.navigateUp() // Kembali ke layar Login
                }
            )
        }
        // Placeholder untuk Home Screen agar aplikasi tidak crash saat navigasi
        composable(Routes.HOME) {
            Text(text = "Selamat Datang! Anda Berhasil Login.")
        }
    }
}