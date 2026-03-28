package com.bitnesttechs.hms.patient.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.features.login.LoginScreen
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.EntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Main : Screen("main")
}

@EntryPoint
@InstallIn(ActivityComponent::class)
interface TokenStorageEntryPoint {
    fun tokenStorage(): TokenStorage
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val tokenStorage = remember {
        EntryPointAccessors.fromActivity(
            context as android.app.Activity,
            TokenStorageEntryPoint::class.java
        ).tokenStorage()
    }

    // Check login state off the main thread to avoid blocking on EncryptedSharedPreferences
    var startDest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        startDest = withContext(Dispatchers.IO) {
            if (tokenStorage.isLoggedIn) Screen.Main.route else Screen.Login.route
        }
    }

    if (startDest == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDest!!) {

        composable(Screen.Login.route) {
            LoginScreen(
                tokenStorage = tokenStorage,
                onLoginSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Main.route) {
            MainScreen(
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
