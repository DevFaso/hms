package com.bitnesttechs.hms.patient.medications.presentation

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
import com.bitnesttechs.hms.patient.medications.data.MedicationDto
import com.bitnesttechs.hms.patient.medications.data.PrescriptionDto
import com.bitnesttechs.hms.patient.medications.data.RefillDto

@Composable
fun MedicationScreen(viewModel: MedicationViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("Medications", modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("Prescriptions", modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text("Refills", modifier = Modifier.padding(16.dp))
            }
        }

        when {
            state.isLoading -> HmsLoadingView("Loading medications...")
            state.error != null -> HmsErrorView(state.error!!) { viewModel.load() }
            else -> when (selectedTab) {
                0 -> MedicationsTab(state.medications)
                1 -> PrescriptionsTab(state.prescriptions, onRefill = { viewModel.requestRefill(it) })
                2 -> RefillsTab(state.refills, onCancel = { viewModel.cancelRefill(it) })
            }
        }
    }
}

@Composable
private fun MedicationsTab(medications: List<MedicationDto>) {
    if (medications.isEmpty()) {
        HmsEmptyState(Icons.Default.Medication, "No Medications", "Your medications will appear here.")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(medications, key = { it.id }) { med ->
                HmsCard {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(med.name ?: "Unknown", style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary)
                            med.dosage?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                            }
                            med.frequency?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp), tint = HmsTextTertiary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
                                }
                            }
                        }
                        HmsStatusBadge(
                            text = med.status ?: "Unknown",
                            color = if (med.isActive) HmsSuccess else HmsTextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrescriptionsTab(prescriptions: List<PrescriptionDto>, onRefill: (String) -> Unit) {
    if (prescriptions.isEmpty()) {
        HmsEmptyState(Icons.Default.Description, "No Prescriptions", "Your prescriptions will appear here.")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(prescriptions, key = { it.id }) { rx ->
                HmsCard {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(rx.medicationName ?: "Prescription", style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary, modifier = Modifier.weight(1f))
                            HmsStatusBadge(
                                text = rx.status ?: "Unknown",
                                color = prescriptionStatusColor(rx.status)
                            )
                        }
                        rx.dosage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            rx.refillsRemaining?.let { refills ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (refills > 0) HmsSuccess else HmsError)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("$refills refills left", style = MaterialTheme.typography.bodySmall, color = if (refills > 0) HmsSuccess else HmsError)
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if ((rx.refillsRemaining ?: 0) > 0 && rx.status?.uppercase() == "ACTIVE") {
                                TextButton(onClick = { onRefill(rx.id) }) {
                                    Text("Request Refill", color = HmsPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefillsTab(refills: List<RefillDto>, onCancel: (String) -> Unit) {
    if (refills.isEmpty()) {
        HmsEmptyState(Icons.Default.Refresh, "No Refills", "Your refill requests will appear here.")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(refills, key = { it.id }) { refill ->
                HmsCard {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(refill.medicationName ?: "Refill", style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary, modifier = Modifier.weight(1f))
                            HmsStatusBadge(text = refill.status ?: "Unknown", color = refillStatusColor(refill.status))
                        }
                        refill.requestedDate?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(14.dp), tint = HmsTextTertiary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Requested: $it", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                            }
                        }
                        refill.completedDate?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = HmsSuccess)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Completed: $it", style = MaterialTheme.typography.bodySmall, color = HmsSuccess)
                            }
                        }
                        if (refill.status?.uppercase() == "PENDING") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onCancel(refill.id) }) {
                                    Text("Cancel", color = HmsError)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun prescriptionStatusColor(status: String?) = when (status?.uppercase()) {
    "ACTIVE" -> HmsSuccess
    "EXPIRED" -> HmsError
    "COMPLETED" -> HmsInfo
    else -> HmsTextSecondary
}

private fun refillStatusColor(status: String?) = when (status?.uppercase()) {
    "PENDING" -> HmsWarning
    "APPROVED", "COMPLETED", "READY" -> HmsSuccess
    "DENIED", "CANCELLED" -> HmsError
    else -> HmsTextSecondary
}
