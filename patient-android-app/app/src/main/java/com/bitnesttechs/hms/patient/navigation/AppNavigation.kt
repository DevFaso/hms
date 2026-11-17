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
import com.bitnesttechs.hms.patient.core.auth.AuthRepository
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.core.push.PushRegistrar
import com.bitnesttechs.hms.patient.features.account.ActivationScreen
import com.bitnesttechs.hms.patient.features.account.ForgotPasswordScreen
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
    object ForgotPassword : Screen("forgot_password")
    object Activation : Screen("activation")
}

@EntryPoint
@InstallIn(ActivityComponent::class)
interface TokenStorageEntryPoint {
    fun tokenStorage(): TokenStorage
    fun authRepository(): AuthRepository
    fun pushRegistrar(): PushRegistrar
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromActivity(
            context as android.app.Activity,
            TokenStorageEntryPoint::class.java
        )
    }
    val tokenStorage = remember { entryPoint.tokenStorage() }

    // Check login state off the main thread to avoid blocking on EncryptedSharedPreferences
    var startDest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        startDest = withContext(Dispatchers.IO) {
            if (tokenStorage.isLoggedIn) Screen.Main.route else Screen.Login.route
        }
        // A session from a build that never stored the HMS user id (every SSO
        // session did so) resolves it now, so chat and notes work without a
        // fresh sign-in.
        if (startDest == Screen.Main.route) {
            entryPoint.authRepository().ensureUserId()
            // Once per cold start: a session from an older build never
            // registered, and a token can rotate while the app is dead.
            entryPoint.pushRegistrar().registerAsync()
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
                },
                onForgotPassword = { navController.navigate(Screen.ForgotPassword.route) },
                onActivateAccount = { navController.navigate(Screen.Activation.route) }
            )
        }

        composable(Screen.ForgotPassword.route) {
            ForgotPasswordScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Activation.route) {
            ActivationScreen(onBack = { navController.popBackStack() })
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
