package com.bitnesttechs.hms.patient.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bitnesttechs.hms.patient.features.appointments.AppointmentsScreen
import com.bitnesttechs.hms.patient.features.billing.BillingScreen
import com.bitnesttechs.hms.patient.features.careteam.CareTeamScreen
import com.bitnesttechs.hms.patient.features.dashboard.DashboardScreen
import com.bitnesttechs.hms.patient.features.documents.DocumentsScreen
import com.bitnesttechs.hms.patient.features.healthrecords.HealthRecordsScreen
import com.bitnesttechs.hms.patient.features.labresults.LabResultsScreen
import com.bitnesttechs.hms.patient.features.medications.MedicationsScreen
import com.bitnesttechs.hms.patient.features.messages.MessagesScreen
import com.bitnesttechs.hms.patient.features.messages.MessageThreadScreen
import com.bitnesttechs.hms.patient.features.notifications.NotificationsScreen
import com.bitnesttechs.hms.patient.features.profile.ProfileScreen
import com.bitnesttechs.hms.patient.features.sharingprivacy.SharingPrivacyScreen
import com.bitnesttechs.hms.patient.features.visits.VisitHistoryScreen
import com.bitnesttechs.hms.patient.features.vitals.VitalsScreen

sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    object Dashboard : Tab("tab_dashboard", "Dashboard", Icons.Default.Home)
    object Appointments : Tab("tab_appointments", "Appointments", Icons.Default.CalendarMonth)
    object Messages : Tab("tab_messages", "Messages", Icons.Default.Message)
    object Profile : Tab("tab_profile", "Profile", Icons.Default.AccountCircle)
}

val bottomTabs = listOf(Tab.Dashboard, Tab.Appointments, Tab.Messages, Tab.Profile)

@Composable
fun MainScreen(onLogout: () -> Unit) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Hide bottom bar on detail screens
    val topLevelRoutes = bottomTabs.map { it.route }.toSet()
    val showBottomBar = currentDestination?.route in topLevelRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ── Tabs ──────────────────────────────────────────────────────────
            composable(Tab.Dashboard.route) {
                DashboardScreen(navController = navController)
            }
            composable(Tab.Appointments.route) {
                AppointmentsScreen()
            }
            composable(Tab.Messages.route) {
                MessagesScreen(
                    onThreadClick = { threadId ->
                        navController.navigate("thread/$threadId")
                    }
                )
            }
            composable(Tab.Profile.route) {
                ProfileScreen(
                    navController = navController,
                    onLogout = onLogout
                )
            }

            // ── Dashboard destinations ────────────────────────────────────────
            composable("lab_results") { LabResultsScreen() }
            composable("medications") { MedicationsScreen() }
            composable("billing") { BillingScreen() }
            composable("vitals") { VitalsScreen() }
            composable("care_team") { CareTeamScreen() }
            composable("visits") { VisitHistoryScreen() }
            composable("notifications") { NotificationsScreen() }
            composable("documents") { DocumentsScreen() }
            composable("health_records") { HealthRecordsScreen() }
            composable("sharing_privacy") {
                SharingPrivacyScreen()
            }

            // ── Message thread ────────────────────────────────────────────────
            composable("thread/{threadId}") { backStack ->
                val threadId = backStack.arguments?.getString("threadId")?.toLongOrNull() ?: 0L
                MessageThreadScreen(
                    threadId = threadId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
