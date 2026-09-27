package com.bitnesttechs.hms.patient.features.login

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
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
import com.bitnesttechs.hms.patient.core.auth.AuthResult
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.ui.theme.OnBrandMuted
import com.bitnesttechs.hms.patient.ui.theme.BrandPrimary
import com.bitnesttechs.hms.patient.ui.theme.BrandPrimaryDark
import com.bitnesttechs.hms.patient.ui.theme.NeutralGrey
import javax.inject.Inject

@Composable
fun LoginScreen(
    tokenStorage: TokenStorage,
    onLoginSuccess: () -> Unit,
    onForgotPassword: () -> Unit = {},
    onActivateAccount: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val ssoEnabled by viewModel.ssoEnabled.collectAsState()
    val context = LocalContext.current

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Launcher for the AppAuth custom-tab authorization flow (KC-3).
    val ssoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null) {
            viewModel.completeSsoLogin(data)
        }
    }

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
        uiState.error?.let { error ->
            Toast.makeText(context, error.text(context), Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(BrandPrimary, BrandPrimaryDark))
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
                stringResource(R.string.medihub),
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.patient_portal),
                color = OnBrandMuted,
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
                    val mfa = uiState.mfa
                    if (mfa != null) {
                        MfaStep(
                            challenge = mfa,
                            error = uiState.mfaError,
                            busy = uiState.isLoading,
                            onVerify = viewModel::verifyMfa,
                            onCancel = viewModel::cancelMfa
                        )
                        return@Column
                    }
                    Text(
                        stringResource(R.string.sign_in),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1C1B1F)
                    )

                    // Force dark text colors on white card (fixes dark mode white-on-white)
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF1C1B1F),
                        unfocusedTextColor = Color(0xFF1C1B1F),
                        focusedLabelColor = BrandPrimary,
                        unfocusedLabelColor = NeutralGrey,
                        focusedLeadingIconColor = BrandPrimary,
                        unfocusedLeadingIconColor = NeutralGrey,
                        focusedTrailingIconColor = BrandPrimary,
                        unfocusedTrailingIconColor = NeutralGrey,
                        focusedBorderColor = BrandPrimary,
                        unfocusedBorderColor = NeutralGrey,
                        cursorColor = BrandPrimary
                    )

                    // Username
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.username_label)) },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        )
                    )

                    // Password
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password_label)) },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) stringResource(R.string.hide_password) else stringResource(R.string.show_password)
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = fieldColors,
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
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.sign_in), fontSize = 16.sp)
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
                            Icon(Icons.Default.Fingerprint, null, tint = BrandPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.biometric_sign_in), color = BrandPrimary)
                        }
                    }

                    // SSO (Keycloak) button — flag-gated, hidden until KC-3 goes live
                    AnimatedVisibility(visible = ssoEnabled) {
                        OutlinedButton(
                            onClick = { viewModel.startSsoLogin { ssoLauncher.launch(it) } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            enabled = !uiState.isLoading
                        ) {
                            Icon(Icons.Default.Lock, null, tint = BrandPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sso_sign_in), color = BrandPrimary)
                        }
                    }

                    // The backend answers 401 for an inactive account as for a
                    // wrong password, so after one the activation flow is named.
                    if (uiState.showActivationHint) {
                        Text(
                            stringResource(R.string.login_inactive_hint),
                            color = Color(0xFF1C1B1F),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Forgot password
                    TextButton(
                        onClick = onForgotPassword,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            stringResource(R.string.forgot_password),
                            color = BrandPrimaryDark,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // Activation (resend the e-mail, or activate with its link)
                    TextButton(
                        onClick = onActivateAccount,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            stringResource(R.string.resend_activation),
                            color = BrandPrimaryDark,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.copyright),
                color = OnBrandMuted,
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
        Toast.makeText(context, context.getString(R.string.biometric_not_available), Toast.LENGTH_SHORT).show()
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
        .setTitle(context.getString(R.string.biometric_prompt_title))
        .setSubtitle(context.getString(R.string.biometric_prompt_subtitle))
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    prompt.authenticate(promptInfo)
}

/** The app's own headline, then the server's sentence when it sent one. */
internal fun AuthResult.Error.text(context: android.content.Context): String {
    val headline = context.getString(messageRes)
    return detail?.takeIf { it.isNotBlank() }?.let { "$headline\n$it" } ?: headline
}

/**
 * The MFA challenge, inside the sign-in card. A code from an authenticator or
 * a backup code; an account with no factor yet is sent to the web portal,
 * where enrolment lives.
 */
@Composable
private fun MfaStep(
    challenge: AuthResult.MfaRequired,
    error: AuthResult.Error?,
    busy: Boolean,
    onVerify: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    val dark = Color(0xFF1C1B1F)
    Text(
        stringResource(R.string.mfa_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = dark
    )
    if (!challenge.enrolled) {
        Text(stringResource(R.string.mfa_not_enrolled), color = dark, style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { com.bitnesttechs.hms.patient.features.account.openWebPortal(context, "/login") },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
        ) { Text(stringResource(R.string.open_web_portal)) }
    } else {
        Text(stringResource(R.string.mfa_instruction), color = dark, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.take(8) },
            label = { Text(stringResource(R.string.mfa_code_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onVerify(code) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = dark,
                unfocusedTextColor = dark,
                focusedBorderColor = BrandPrimary,
                focusedLabelColor = BrandPrimary,
                cursorColor = BrandPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Text(it.text(context), color = com.bitnesttechs.hms.patient.ui.theme.StatusNegativeOnLight, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { onVerify(code) },
            enabled = code.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(stringResource(R.string.mfa_verify), fontSize = 16.sp)
        }
    }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.account_back_to_sign_in), color = BrandPrimaryDark)
    }
}
