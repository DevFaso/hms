package com.bitnesttechs.hms.patient.settings.presentation

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.bitnesttechs.hms.patient.core.auth.AuthViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLogout: () -> Unit, authViewModel: AuthViewModel? = null) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("hms_settings", Context.MODE_PRIVATE) }
    var biometricEnabled by remember { mutableStateOf(prefs.getBoolean("biometric_enabled", false)) }
    val biometricAvailable = authViewModel?.biometricHelper?.isAvailable ?: false

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HmsSectionHeader(title = "Account")
            SettingsItem(Icons.Default.Person, "Edit Profile")
            SettingsItem(Icons.Default.Lock, "Change Password")

            if (biometricAvailable) {
                HmsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = HmsPrimary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Biometric Login",
                            style = MaterialTheme.typography.bodyLarge,
                            color = HmsTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    scope.launch {
                                        val activity = context as? FragmentActivity ?: return@launch
                                        try {
                                            val success = authViewModel!!.biometricHelper.authenticate(
                                                activity = activity,
                                                title = "Enable Biometric Login",
                                                subtitle = "Verify your identity to enable biometric login"
                                            )
                                            if (success) {
                                                biometricEnabled = true
                                                authViewModel.setBiometricEnabled(true)
                                            }
                                        } catch (_: SecurityException) {}
                                    }
                                } else {
                                    biometricEnabled = false
                                    authViewModel!!.setBiometricEnabled(false)
                                }
                            }
                        )
                    }
                }
            } else {
                SettingsItem(Icons.Default.Fingerprint, "Biometric Login (Unavailable)")
            }

            Spacer(modifier = Modifier.height(8.dp))
            HmsSectionHeader(title = "Preferences")
            SettingsItem(Icons.Default.Notifications, "Notifications")
            SettingsItem(Icons.Default.Language, "Language")

            Spacer(modifier = Modifier.height(8.dp))
            HmsSectionHeader(title = "About")
            SettingsItem(Icons.Default.Info, "Version 1.0.0 (1)")
            SettingsItem(Icons.Default.Description, "Terms of Service")
            SettingsItem(Icons.Default.PrivacyTip, "Privacy Policy")

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = HmsError)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out")
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Sign Out") },
            text = { Text("Are you sure you want to sign out?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) { Text("Sign Out", color = HmsError) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsItem(icon: ImageVector, title: String) {
    HmsCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null, tint = HmsPrimary)
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, color = HmsTextPrimary)
        }
    }
}
