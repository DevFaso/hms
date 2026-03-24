package com.bitnesttechs.hms.patient.auth.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.loginSuccess) {
        if (state.loginSuccess) onLoginSuccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(80.dp))

        // Logo
        Icon(
            Icons.Default.MonitorHeart,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = HmsPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "HMS Patient",
            style = MaterialTheme.typography.headlineLarge,
            color = HmsTextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Sign in to access your health records",
            style = MaterialTheme.typography.bodyMedium,
            color = HmsTextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Username
        HmsTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = "Username or Email",
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Password
        HmsTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = "Password",
            isPassword = true,
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
        )

        // Error
        if (state.errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                state.errorMessage!!,
                color = HmsError,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Login button
        HmsPrimaryButton(
            text = "Sign In",
            onClick = viewModel::login,
            isLoading = state.isLoading,
            enabled = state.username.isNotBlank() && state.password.isNotBlank()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Footer links
        TextButton(onClick = { /* TODO: forgot password */ }) {
            Text("Forgot Password?", color = HmsPrimary)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Don't have an account?", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
            TextButton(onClick = { /* TODO: register */ }) {
                Text("Register", color = HmsPrimary)
            }
        }
    }
}
