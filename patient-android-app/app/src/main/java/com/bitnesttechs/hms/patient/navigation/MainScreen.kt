package com.bitnesttechs.hms.patient.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bitnesttechs.hms.patient.features.appointments.AppointmentsScreen
import com.bitnesttechs.hms.patient.features.appointments.AppointmentDetailScreen
import com.bitnesttechs.hms.patient.features.billing.BillingScreen
import com.bitnesttechs.hms.patient.features.careteam.CareTeamScreen
import com.bitnesttechs.hms.patient.features.dashboard.DashboardScreen
import com.bitnesttechs.hms.patient.features.documents.DocumentsScreen
import com.bitnesttechs.hms.patient.features.familyaccess.FamilyAccessScreen
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
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import kotlinx.coroutines.launch

sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    object Dashboard : Tab("tab_dashboard", "Dashboard", Icons.Default.Home)
    object Appointments : Tab("tab_appointments", "Appointments", Icons.Default.CalendarMonth)
    object Messages : Tab("tab_messages", "Messages", Icons.Default.Message)
    object Profile : Tab("tab_profile", "Profile", Icons.Default.AccountCircle)
}

val bottomTabs = listOf(Tab.Dashboard, Tab.Appointments, Tab.Messages, Tab.Profile)

data class DrawerItem(val label: String, val icon: ImageVector, val route: String)

val drawerItems = listOf(
    DrawerItem("Dashboard", Icons.Default.Home, "tab_dashboard"),
    DrawerItem("Appointments", Icons.Default.CalendarMonth, "tab_appointments"),
    DrawerItem("Lab Results", Icons.Default.Science, "lab_results"),
    DrawerItem("Medications", Icons.Default.Medication, "medications"),
    DrawerItem("Vitals", Icons.Default.Favorite, "vitals"),
    DrawerItem("Billing", Icons.Default.Receipt, "billing"),
    DrawerItem("Care Team", Icons.Default.Group, "care_team"),
    DrawerItem("Visit History", Icons.Default.History, "visits"),
    DrawerItem("Documents", Icons.Default.Description, "documents"),
    DrawerItem("Health Records", Icons.Default.FolderShared, "health_records"),
    DrawerItem("Notifications", Icons.Default.Notifications, "notifications"),
    DrawerItem("Messages", Icons.Default.Message, "tab_messages"),
    DrawerItem("Privacy", Icons.Default.Security, "sharing_privacy"),
    DrawerItem("Family Access", Icons.Default.People, "family_access"),
    DrawerItem("Profile", Icons.Default.AccountCircle, "tab_profile")
)

@Composable
fun MainScreen(onLogout: () -> Unit) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Hide bottom bar on detail screens
    val topLevelRoutes = bottomTabs.map { it.route }.toSet()
    val showBottomBar = currentDestination?.route in topLevelRoutes

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                // Header
                Surface(
                    color = BrandBlue,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("MediHub", style = MaterialTheme.typography.headlineSmall,
                            color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                        Text("Patient Portal", style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Menu items
                drawerItems.forEach { item ->
                    val selected = currentDestination?.route == item.route
                    NavigationDrawerItem(
                        icon = { Icon(item.icon, null) },
                        label = { Text(item.label) },
                        selected = selected,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
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
                    DashboardScreen(
                        navController = navController,
                        onMenuClick = { scope.launch { drawerState.open() } }
                    )
                }
                composable(Tab.Appointments.route) {
                    AppointmentsScreen(navController = navController)
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
                composable("family_access") {
                    FamilyAccessScreen()
                }

                // ── Appointment detail ────────────────────────────────────────────
                composable("appointment_detail") {
                    val appointment = navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.get<com.bitnesttechs.hms.patient.core.models.AppointmentDto>("appointment")
                    if (appointment != null) {
                        AppointmentDetailScreen(
                            appointment = appointment,
                            onBack = { navController.popBackStack() },
                            onCancel = { reason ->
                                navController.popBackStack()
                            }
                        )
                    }
                }

                // ── Message thread ────────────────────────────────────────────────
                composable("thread/{threadId}") { backStack ->
                    val threadId = backStack.arguments?.getString("threadId") ?: ""
                    MessageThreadScreen(
                        threadId = threadId,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
