package com.bitnesttechs.hms.patient.features.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bitnesttechs.hms.patient.core.models.AppointmentDto
import com.bitnesttechs.hms.patient.core.models.LabResultDto
import com.bitnesttechs.hms.patient.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    onMenuClick: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                    }
                },
                title = {
                    Column {
                        Text("MediHub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Patient Portal", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandBlue,
                    titleContentColor = Color.White
                ),
                actions = {
                    BadgedBox(
                        badge = {
                            if (uiState.unreadNotificationCount > 0) {
                                Badge { Text(uiState.unreadNotificationCount.toString()) }
                            }
                        }
                    ) {
                        IconButton(onClick = { navController.navigate("notifications") }) {
                            Icon(Icons.Default.Notifications, null, tint = Color.White)
                        }
                    }
                    IconButton(onClick = { viewModel.loadDashboard() }) {
                        Icon(Icons.Default.Refresh, null, tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Health summary card
            uiState.healthSummary?.let { summary ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = BrandBlue)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            StatChip("Medications", summary.medicationCount.toString(), Icons.Default.Medication)
                            StatChip("Lab Results", summary.labResultCount.toString(), Icons.Default.Science)
                        }
                    }
                }

                // Allergies
                if (!summary.allergies.isNullOrEmpty()) {
                    item {
                        SectionCard(title = "Allergies") {
                            summary.allergies.forEach { allergy ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(allergy) },
                                    leadingIcon = { Icon(Icons.Default.Warning, null, Modifier.size(16.dp)) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = ErrorRed.copy(alpha = 0.1f),
                                        labelColor = ErrorRed
                                    )
                                )
                            }
                        }
                    }
                }

                // Active conditions
                if (!summary.activeDiagnoses.isNullOrEmpty()) {
                    item {
                        SectionCard(title = "Active Conditions") {
                            summary.activeDiagnoses.forEach { condition ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(8.dp).clip(CircleShape)
                                            .background(BrandBlue)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(condition, style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }

                // Chronic conditions
                if (!summary.chronicConditions.isNullOrEmpty()) {
                    item {
                        SectionCard(title = "Chronic Conditions") {
                            summary.chronicConditions.forEach { condition ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(8.dp).clip(CircleShape)
                                            .background(WarningAmber)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(condition, style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }

            // Quick links
            item {
                SectionCard(title = "Quick Access") {
                    val quickLinks = listOf(
                        Triple("Appointments", Icons.Default.CalendarMonth, "tab_appointments"),
                        Triple("Lab Results", Icons.Default.Science, "lab_results"),
                        Triple("Medications", Icons.Default.Medication, "medications"),
                        Triple("Billing", Icons.Default.Receipt, "billing"),
                        Triple("Vitals", Icons.Default.Favorite, "vitals"),
                        Triple("Care Team", Icons.Default.Group, "care_team"),
                        Triple("Visits", Icons.Default.History, "visits"),
                        Triple("Documents", Icons.Default.Description, "documents")
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickLinks.chunked(4).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                rowItems.forEach { (label, icon, route) ->
                                    QuickLinkItem(label, icon) { navController.navigate(route) }
                                }
                            }
                        }
                    }
                }
            }

            // Upcoming appointments
            if (uiState.upcomingAppointments.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "Upcoming Appointments",
                        action = { TextButton(onClick = { navController.navigate("tab_appointments") }) {
                            Text("See all")
                        }}
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.upcomingAppointments.forEach { appt ->
                                AppointmentRow(appt)
                            }
                        }
                    }
                }
            }

            // Recent lab results
            if (uiState.recentLabResults.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "Recent Lab Results",
                        action = { TextButton(onClick = { navController.navigate("lab_results") }) {
                            Text("See all")
                        }}
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.recentLabResults.take(3).forEach { lab ->
                                LabResultRow(lab)
                            }
                        }
                    }
                }
            }

            // Error
            uiState.error?.let {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f))) {
                        Text(
                            "Error: $it",
                            modifier = Modifier.padding(16.dp),
                            color = ErrorRed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                action?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun RowScope.StatChip(label: String, value: String, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun QuickLinkItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                .background(BrandLightBlue),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AppointmentRow(appt: AppointmentDto) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(appt.staffName ?: "Unknown Doctor", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(appt.departmentName ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${appt.appointmentDate} ${appt.timeDisplay ?: ""}".trim(), style = MaterialTheme.typography.bodySmall)
        }
        StatusBadge(appt.statusDisplay)
    }
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun LabResultRow(lab: LabResultDto) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(lab.testName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(lab.resultDate ?: lab.collectionDate ?: "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusBadge(
            text = lab.statusDisplay,
            color = when {
                lab.isCritical -> CriticalRed
                lab.isAbnormal -> WarningAmber
                else -> SuccessGreen
            }
        )
    }
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun StatusBadge(text: String, color: Color = BrandBlue) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}
