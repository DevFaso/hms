package com.bitnesttechs.hms.patient.home.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        when {
            state.isLoading -> HmsLoadingView("Loading your health summary...")
            state.errorMessage != null -> HmsErrorView(state.errorMessage!!) { viewModel.load() }
            else -> {
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Greeting card
                    HmsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Welcome back,", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                                Text("Patient", style = MaterialTheme.typography.headlineMedium, color = HmsTextPrimary)
                            }
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = HmsPrimary
                            )
                        }
                    }

                    // Quick actions
                    HmsSectionHeader(title = "Quick Actions")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuickAction(Icons.Default.CalendarMonth, "Book\nAppointment", HmsPrimary, Modifier.weight(1f))
                        QuickAction(Icons.Default.Medication, "My\nMedications", HmsAccent, Modifier.weight(1f))
                        QuickAction(Icons.Default.Science, "Lab\nResults", HmsWarning, Modifier.weight(1f))
                        QuickAction(Icons.Default.Chat, "Messages", HmsInfo, Modifier.weight(1f))
                    }

                    // Health summary
                    state.summary?.let { summary ->
                        HmsSectionHeader(title = "Health Summary")
                        HmsCard {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SummaryRow("Upcoming Appointments", "${summary.upcomingAppointments}")
                                HorizontalDivider(color = HmsDivider)
                                SummaryRow("Active Medications", "${summary.activeMedications}")
                                HorizontalDivider(color = HmsDivider)
                                SummaryRow("Pending Lab Results", "${summary.pendingLabResults}")
                                HorizontalDivider(color = HmsDivider)
                                SummaryRow("Unread Messages", "${summary.unreadMessages}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, color: Color, modifier: Modifier = Modifier) {
    HmsCard(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = color)
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = HmsTextPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = HmsTextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = HmsTextPrimary)
    }
}
