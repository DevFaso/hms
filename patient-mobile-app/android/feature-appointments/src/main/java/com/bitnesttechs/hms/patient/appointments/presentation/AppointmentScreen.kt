package com.bitnesttechs.hms.patient.appointments.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.appointments.data.AppointmentDto
import com.bitnesttechs.hms.patient.core.designsystem.*

@Composable
fun AppointmentScreen(
    viewModel: AppointmentViewModel = hiltViewModel(),
    onAppointmentClick: (AppointmentDto) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("Upcoming", modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("Past", modifier = Modifier.padding(16.dp))
            }
        }

        when {
            state.isLoading -> HmsLoadingView("Loading appointments...")
            state.error != null -> HmsErrorView(state.error!!) { viewModel.load() }
            else -> {
                val appointments = if (selectedTab == 0) state.upcoming else state.past
                if (appointments.isEmpty()) {
                    HmsEmptyState(
                        icon = if (selectedTab == 0) Icons.Default.CalendarMonth else Icons.Default.History,
                        title = if (selectedTab == 0) "No Upcoming Appointments" else "No Past Appointments",
                        message = if (selectedTab == 0) "You don't have any scheduled appointments."
                        else "Your appointment history will appear here."
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(appointments, key = { it.id }) { appointment ->
                            AppointmentCard(
                                appointment = appointment,
                                onClick = { onAppointmentClick(appointment) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppointmentCard(appointment: AppointmentDto, onClick: () -> Unit) {
    HmsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appointment.appointmentType ?: "Appointment",
                    style = MaterialTheme.typography.titleSmall,
                    color = HmsTextPrimary
                )
                appointment.doctorName?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                }
            }
            HmsStatusBadge(
                text = appointment.status ?: "Unknown",
                color = appointmentStatusColor(appointment.status)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp), tint = HmsTextTertiary)
                Spacer(modifier = Modifier.width(4.dp))
                Text(appointment.appointmentDate ?: "N/A", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
            }
            Row {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = HmsTextTertiary)
                Spacer(modifier = Modifier.width(4.dp))
                Text(appointment.appointmentTime ?: "N/A", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
            }
        }
        appointment.departmentName?.let { dept ->
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Icon(Icons.Default.Business, contentDescription = null, modifier = Modifier.size(16.dp), tint = HmsTextTertiary)
                Spacer(modifier = Modifier.width(4.dp))
                Text(dept, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
            }
        }
    }
}

private fun appointmentStatusColor(status: String?) = when (status?.uppercase()) {
    "SCHEDULED", "CONFIRMED" -> HmsSuccess
    "COMPLETED" -> HmsInfo
    "CANCELLED", "NO_SHOW" -> HmsError
    "PENDING" -> HmsWarning
    else -> HmsTextSecondary
}
