package com.bitnesttechs.hms.patient.features.login

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandDarkBlue
import javax.inject.Inject

@Composable
fun LoginScreen(
    tokenStorage: TokenStorage,
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Check saved credentials for biometric button
    val hasSavedCredentials = remember { tokenStorage.savedUsername != null }
    LaunchedEffect(Unit) {
        viewModel.checkBiometricAvailability(hasSavedCredentials)
    }

    // Navigate on success
    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) onLoginSuccess()
    }

    // Show error toast
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(BrandBlue, BrandDarkBlue))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo / Title
            Icon(
                imageVector = Icons.Default.LocalHospital,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "MediHub",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Patient Portal",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 16.sp
            )
            Spacer(Modifier.height(40.dp))

            // Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Sign In",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // Username
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username or Email") },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        )
                    )

                    // Password
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (username.isNotBlank() && password.isNotBlank()) {
                                    viewModel.login(username, password)
                                }
                            }
                        )
                    )

                    // Login button
                    Button(
                        onClick = { viewModel.login(username, password) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = username.isNotBlank() && password.isNotBlank() && !uiState.isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Sign In", fontSize = 16.sp)
                        }
                    }

                    // Biometric button
                    AnimatedVisibility(visible = hasSavedCredentials) {
                        OutlinedButton(
                            onClick = { launchBiometric(context, viewModel) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Icon(Icons.Default.Fingerprint, null, tint = BrandBlue)
                            Spacer(Modifier.width(8.dp))
                            Text("Sign in with Biometrics", color = BrandBlue)
                        }
                    }

                    // Forgot password
                    TextButton(
                        onClick = { /* TODO: forgot password flow */ },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            "Forgot Password?",
                            color = BrandDarkBlue,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "MediHub HMS © 2026",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun launchBiometric(context: android.content.Context, viewModel: LoginViewModel) {
    val biometricManager = BiometricManager.from(context)
    val canAuthenticate = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
        Toast.makeText(context, "Biometric not available", Toast.LENGTH_SHORT).show()
        return
    }

    val executor = ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(
        context as FragmentActivity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                viewModel.biometricLogin()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
            }
        }
    )
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Sign in to MediHub")
        .setSubtitle("Use your biometric credential")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    prompt.authenticate(promptInfo)
}
