package com.bitnesttechs.hms.patient.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bitnesttechs.hms.patient.auth.presentation.LoginScreen
import com.bitnesttechs.hms.patient.core.auth.AuthState
import com.bitnesttechs.hms.patient.core.auth.AuthViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import kotlinx.coroutines.launch

@Composable
fun AppNavGraph(authViewModel: AuthViewModel = hiltViewModel()) {
    val authState by authViewModel.authState.collectAsState()

    when (authState) {
        is AuthState.Authenticated -> MainNavHost(authViewModel = authViewModel, onLogout = { authViewModel.logout() })
        is AuthState.BiometricLocked -> BiometricLockScreen(authViewModel)
        else -> LoginScreen(
            onLoginSuccess = { authViewModel.onLoginSuccess() },
        )
    }
}

@Composable
private fun BiometricLockScreen(authViewModel: AuthViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val activity = context as? FragmentActivity ?: return@LaunchedEffect
        try {
            val success = authViewModel.biometricHelper.authenticate(
                activity = activity,
                title = "Unlock HMS",
                subtitle = "Authenticate to access your health records"
            )
            if (success) authViewModel.onBiometricSuccess()
        } catch (_: SecurityException) {
            // Biometric error — user can tap button to retry
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = HmsSurface) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Fingerprint,
                contentDescription = "Biometric",
                modifier = Modifier.size(80.dp),
                tint = HmsPrimary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text("App Locked", style = MaterialTheme.typography.headlineMedium, color = HmsTextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Authenticate to continue", style = MaterialTheme.typography.bodyMedium, color = HmsTextSecondary)
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = {
                scope.launch {
                    val activity = context as? FragmentActivity ?: return@launch
                    try {
                        val success = authViewModel.biometricHelper.authenticate(activity)
                        if (success) authViewModel.onBiometricSuccess()
                    } catch (_: SecurityException) {}
                }
            }) {
                Text("Unlock")
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { authViewModel.skipBiometric() }) {
                Text("Use Password Instead")
            }
        }
    }
}

@Composable
private fun MainNavHost(authViewModel: AuthViewModel, onLogout: () -> Unit) {
    val navController = rememberNavController()
    val tabs = listOf(BottomTab.Home, BottomTab.Appointments, BottomTab.Records, BottomTab.Billing, BottomTab.More)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                tabs.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = BottomTab.Home.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(BottomTab.Home.route) {
                com.bitnesttechs.hms.patient.home.presentation.HomeScreen()
            }
            composable(BottomTab.Appointments.route) {
                com.bitnesttechs.hms.patient.appointments.presentation.AppointmentScreen()
            }
            composable(BottomTab.Records.route) {
                com.bitnesttechs.hms.patient.records.presentation.RecordsScreen()
            }
            composable(BottomTab.Billing.route) {
                com.bitnesttechs.hms.patient.billing.presentation.BillingScreen()
            }
            composable(BottomTab.More.route) {
                com.bitnesttechs.hms.patient.more.MoreScreen(
                    navController = navController,
                    onLogout = onLogout
                )
            }
            composable("profile") {
                com.bitnesttechs.hms.patient.profile.presentation.ProfileScreen()
            }
            composable("settings") {
                com.bitnesttechs.hms.patient.settings.presentation.SettingsScreen(
                    onLogout = onLogout,
                    authViewModel = authViewModel
                )
            }
            composable("medications") {
                com.bitnesttechs.hms.patient.medications.presentation.MedicationScreen()
            }
            composable("notifications") {
                com.bitnesttechs.hms.patient.notifications.presentation.NotificationScreen()
            }
            composable("lab-results") {
                com.bitnesttechs.hms.patient.labresults.presentation.LabResultsScreen()
            }
            composable("vitals") {
                com.bitnesttechs.hms.patient.vitals.presentation.VitalsScreen()
            }
            composable("care-team") {
                com.bitnesttechs.hms.patient.careteam.presentation.CareTeamScreen()
            }
            composable("chat") {
                com.bitnesttechs.hms.patient.chat.presentation.ChatScreen(currentUserId = "")
            }
            composable("consents") {
                com.bitnesttechs.hms.patient.consents.presentation.ConsentsScreen()
            }
            composable("access-logs") {
                com.bitnesttechs.hms.patient.accesslogs.presentation.AccessLogsScreen()
            }
            composable("immunizations") {
                com.bitnesttechs.hms.patient.immunizations.presentation.ImmunizationsScreen()
            }
            composable("treatment-plans") {
                com.bitnesttechs.hms.patient.treatmentplans.presentation.TreatmentPlansScreen()
            }
            composable("referrals") {
                com.bitnesttechs.hms.patient.referrals.presentation.ReferralsScreen()
            }
            composable("consultations") {
                com.bitnesttechs.hms.patient.consultations.presentation.ConsultationsScreen()
            }
            composable("documents") {
                com.bitnesttechs.hms.patient.documents.presentation.DocumentsScreen()
            }
        }
    }
}

sealed class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    data object Home : BottomTab("home", "Home", Icons.Default.Home)
    data object Appointments : BottomTab("appointments", "Appointments", Icons.Default.CalendarMonth)
    data object Records : BottomTab("records", "Records", Icons.Default.MedicalInformation)
    data object Billing : BottomTab("billing", "Billing", Icons.Default.CreditCard)
    data object More : BottomTab("more", "More", Icons.Default.MoreHoriz)
}
