package com.bitnesttechs.hms.patient.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
import com.bitnesttechs.hms.patient.features.visitsummaries.VisitSummariesScreen
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import kotlinx.coroutines.launch

sealed class Tab(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    object Dashboard : Tab("tab_dashboard", R.string.dashboard, Icons.Default.Home)
    object Appointments : Tab("tab_appointments", R.string.appointments, Icons.Default.CalendarMonth)
    object Messages : Tab("tab_messages", R.string.messages, Icons.Default.Message)
    object Profile : Tab("tab_profile", R.string.profile, Icons.Default.AccountCircle)
}

val bottomTabs = listOf(Tab.Dashboard, Tab.Appointments, Tab.Messages, Tab.Profile)

data class DrawerItem(@StringRes val labelRes: Int, val icon: ImageVector, val route: String)

val drawerItems = listOf(
    DrawerItem(R.string.dashboard, Icons.Default.Home, "tab_dashboard"),
    DrawerItem(R.string.appointments, Icons.Default.CalendarMonth, "tab_appointments"),
    DrawerItem(R.string.lab_results, Icons.Default.Science, "lab_results"),
    DrawerItem(R.string.medications, Icons.Default.Medication, "medications"),
    DrawerItem(R.string.vitals, Icons.Default.Favorite, "vitals"),
    DrawerItem(R.string.billing, Icons.Default.Receipt, "billing"),
    DrawerItem(R.string.care_team, Icons.Default.Group, "care_team"),
    DrawerItem(R.string.visit_history, Icons.Default.History, "visits"),
    DrawerItem(R.string.after_visit_summaries, Icons.Default.Description, "visit_summaries"),
    DrawerItem(R.string.documents, Icons.Default.Description, "documents"),
    DrawerItem(R.string.health_records, Icons.Default.FolderShared, "health_records"),
    DrawerItem(R.string.notifications, Icons.Default.Notifications, "notifications"),
    DrawerItem(R.string.messages, Icons.Default.Message, "tab_messages"),
    DrawerItem(R.string.privacy, Icons.Default.Security, "sharing_privacy"),
    DrawerItem(R.string.family_access, Icons.Default.People, "family_access"),
    DrawerItem(R.string.profile, Icons.Default.AccountCircle, "tab_profile")
)

@Composable
fun MainScreen(onLogout: () -> Unit) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Show bottom bar on tab routes AND drawer sub-screens
    val hideBottomBarRoutes = setOf("thread/{threadId}", "appointment_detail")
    val showBottomBar = currentDestination?.route !in hideBottomBarRoutes

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Header
                Surface(
                    color = BrandBlue,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(stringResource(R.string.medihub), style = MaterialTheme.typography.headlineSmall,
                            color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.patient_portal), style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Menu items
                drawerItems.forEach { item ->
                    val selected = currentDestination?.route == item.route
                    NavigationDrawerItem(
                        icon = { Icon(item.icon, null) },
                        label = { Text(stringResource(item.labelRes)) },
                        selected = selected,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                }
            }
        }
    ) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        val currentRoute = currentDestination?.route
                        // Map sub-screens to their parent tab
                        val dashboardSubRoutes = setOf(
                            "lab_results", "medications", "billing", "vitals",
                            "care_team", "visits", "visit_summaries", "documents", "health_records",
                            "notifications", "sharing_privacy", "family_access"
                        )
                        val activeTab = when (currentRoute) {
                            in dashboardSubRoutes -> Tab.Dashboard.route
                            "appointment_detail" -> Tab.Appointments.route
                            "thread/{threadId}" -> Tab.Messages.route
                            else -> currentRoute
                        }

                        bottomTabs.forEach { tab ->
                            val selected = activeTab == tab.route
                            NavigationBarItem(
                                icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                                label = { Text(stringResource(tab.labelRes)) },
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            inclusive = false
                                        }
                                        launchSingleTop = true
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
                    AppointmentsScreen(
                        navController = navController,
                        onMenuClick = { scope.launch { drawerState.open() } }
                    )
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
                composable("lab_results") { LabResultsScreen(onBack = { navController.popBackStack() }) }
                composable("medications") { MedicationsScreen(onBack = { navController.popBackStack() }) }
                composable("billing") { BillingScreen(onBack = { navController.popBackStack() }) }
                composable("vitals") { VitalsScreen(onBack = { navController.popBackStack() }) }
                composable("care_team") { CareTeamScreen(onBack = { navController.popBackStack() }) }
                composable("visits") { VisitHistoryScreen(onBack = { navController.popBackStack() }) }
                composable("visit_summaries") { VisitSummariesScreen(onBack = { navController.popBackStack() }) }
                composable("notifications") { NotificationsScreen(onBack = { navController.popBackStack() }) }
                composable("documents") { DocumentsScreen(onBack = { navController.popBackStack() }) }
                composable("health_records") { HealthRecordsScreen(onBack = { navController.popBackStack() }) }
                composable("sharing_privacy") {
                    SharingPrivacyScreen(onBack = { navController.popBackStack() })
                }
                composable("family_access") {
                    FamilyAccessScreen(
                        onBack = { navController.popBackStack() },
                        navController = navController
                    )
                }
                composable("proxy_data/{patientId}/{permission}/{patientName}") { backStackEntry ->
                    val patientId = backStackEntry.arguments?.getString("patientId") ?: ""
                    val permission = backStackEntry.arguments?.getString("permission") ?: ""
                    val patientName = backStackEntry.arguments?.getString("patientName") ?: "Patient"
                    com.bitnesttechs.hms.patient.features.familyaccess.ProxyDataScreen(
                        patientId = patientId,
                        permission = permission,
                        patientName = patientName,
                        onBack = { navController.popBackStack() }
                    )
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
