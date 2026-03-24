package com.bitnesttechs.hms.patient.records.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import com.bitnesttechs.hms.patient.records.data.EncounterDto
import com.bitnesttechs.hms.patient.records.data.VisitSummaryDto

@Composable
fun RecordsScreen(viewModel: RecordsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("Visits", modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("Summaries", modifier = Modifier.padding(16.dp))
            }
        }

        when {
            state.isLoading -> HmsLoadingView("Loading records...")
            state.error != null -> HmsErrorView(state.error!!) { viewModel.load() }
            else -> when (selectedTab) {
                0 -> EncountersTab(state.encounters)
                1 -> SummariesTab(state.summaries)
            }
        }
    }
}

@Composable
private fun EncountersTab(encounters: List<EncounterDto>) {
    if (encounters.isEmpty()) {
        HmsEmptyState(Icons.Default.MedicalInformation, "No Visit History", "Your visit records will appear here.")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(encounters, key = { it.id }) { encounter ->
                HmsCard {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(encounter.encounterType ?: "Visit", style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary)
                            encounter.doctorName?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.MedicalServices, contentDescription = null, modifier = Modifier.size(14.dp), tint = HmsTextSecondary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                                }
                            }
                        }
                        HmsStatusBadge(text = encounter.status ?: "Unknown", color = encounterStatusColor(encounter.status))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        encounter.encounterDate?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(14.dp), tint = HmsTextTertiary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
                            }
                        }
                        encounter.departmentName?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Business, contentDescription = null, modifier = Modifier.size(14.dp), tint = HmsTextTertiary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
                            }
                        }
                    }
                    encounter.chiefComplaint?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummariesTab(summaries: List<VisitSummaryDto>) {
    if (summaries.isEmpty()) {
        HmsEmptyState(Icons.Default.Description, "No Summaries", "After-visit summaries will appear here.")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(summaries, key = { it.id }) { summary ->
                HmsCard {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Visit Summary", style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary, modifier = Modifier.weight(1f))
                        summary.visitDate?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
                        }
                    }
                    summary.doctorName?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MedicalServices, contentDescription = null, modifier = Modifier.size(14.dp), tint = HmsTextSecondary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                        }
                    }
                    summary.diagnosis?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary, maxLines = 2)
                    }
                }
            }
        }
    }
}

private fun encounterStatusColor(status: String?) = when (status?.uppercase()) {
    "COMPLETED", "DISCHARGED" -> HmsSuccess
    "IN_PROGRESS", "ACTIVE" -> HmsInfo
    "CANCELLED" -> HmsError
    else -> HmsTextSecondary
}
