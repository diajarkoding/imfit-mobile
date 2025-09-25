package com.diajarkoding.imfit.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.diajarkoding.imfit.presentation.ui.SplashScreen
import com.diajarkoding.imfit.presentation.ui.auth.LoginScreen
import com.diajarkoding.imfit.presentation.ui.auth.RegisterScreen
import com.diajarkoding.imfit.presentation.ui.main.MainScreen

@Composable
fun RootNavigation(
    startDestination: String, // Ditentukan dari MainViewModel (MAIN_GRAPH atau SPLASH)
    showLoading: () -> Unit,
    hideLoading: () -> Unit
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {

        // 🔹 Splash tambahan
        composable(Routes.SPLASH) {
            SplashScreen(
                onTimeout = {
                    navController.navigate(Routes.LOGIN) {
                        // Hapus Splash dari backstack
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        // 🔹 Login langsung di root
        composable(Routes.LOGIN) {
            LoginScreen(
                onNavigateToRegister = { navController.navigate(Routes.REGISTER) },
                onLoginSuccess = {
                    navController.navigate(Routes.MAIN_GRAPH) {
                        // Bersihkan seluruh backstack auth
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                showLoading = showLoading,
                hideLoading = hideLoading
            )
        }

        // 🔹 Register langsung di root
        composable(Routes.REGISTER) {
            RegisterScreen(
                onNavigateToLogin = { navController.navigateUp() },
                onRegisterSuccess = { navController.navigate(Routes.LOGIN) }
            )
        }

        // 🔹 Main graph
        composable(Routes.MAIN_GRAPH) {
            MainScreen(
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        // 🚀 Logout langsung ke Login
                        popUpTo(Routes.MAIN_GRAPH) { inclusive = true }
                    }
                }
            )
        }
    }
}
