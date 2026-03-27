package com.bitnesttechs.hms.patient.features.appointments

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitnesttechs.hms.patient.core.models.AppointmentDto
import com.bitnesttechs.hms.patient.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentDetailScreen(
    appointment: AppointmentDto,
    onBack: () -> Unit,
    onCancel: ((String) -> Unit)? = null,
    onReschedule: ((String, String, String) -> Unit)? = null
) {
    var showCancelDialog by remember { mutableStateOf(false) }
    var cancelReason by remember { mutableStateOf("") }

    val statusColor = when (appointment.status.uppercase()) {
        "SCHEDULED", "CONFIRMED" -> SuccessGreen
        "CANCELLED", "CANCELLED_BY_PATIENT" -> ErrorRed
        "COMPLETED" -> BrandBlue
        "RESCHEDULED" -> WarningOrange
        "NO_SHOW" -> Color.Gray
        else -> WarningOrange
    }
    val statusIcon = when (appointment.status.uppercase()) {
        "SCHEDULED", "CONFIRMED" -> Icons.Default.CheckCircle
        "CANCELLED", "CANCELLED_BY_PATIENT" -> Icons.Default.Cancel
        "COMPLETED" -> Icons.Default.TaskAlt
        "RESCHEDULED" -> Icons.Default.Update
        "NO_SHOW" -> Icons.Default.PersonOff
        else -> Icons.Default.Schedule
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appointment Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandBlue,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status header
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f))
            ) {
                Row(
                    Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(statusIcon, null, Modifier.size(40.dp), tint = statusColor)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            appointment.statusDisplay,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                        Text(
                            "Appointment ${appointment.id.take(8)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Provider card
            DetailCard(
                title = "Provider",
                icon = Icons.Default.Person
            ) {
                DetailRow("Doctor", appointment.staffName ?: "—")
                DetailRow("Department", appointment.departmentName ?: "—")
                DetailRow("Hospital", appointment.hospitalName ?: "—")
            }

            // Schedule card
            DetailCard(
                title = "Schedule",
                icon = Icons.Default.CalendarMonth
            ) {
                DetailRow("Date", appointment.appointmentDate)
                DetailRow("Time", appointment.timeDisplay ?: "—")
            }

            // Visit details
            if (!appointment.reason.isNullOrBlank() || !appointment.notes.isNullOrBlank()) {
                DetailCard(
                    title = "Visit Details",
                    icon = Icons.Default.Description
                ) {
                    if (!appointment.reason.isNullOrBlank()) {
                        DetailRow("Reason", appointment.reason)
                    }
                    if (!appointment.notes.isNullOrBlank()) {
                        DetailRow("Notes", appointment.notes)
                    }
                }
            }

            // Record info
            DetailCard(
                title = "Record Info",
                icon = Icons.Default.Info
            ) {
                DetailRow("Appointment ID", appointment.id.take(8) + "…")
                DetailRow("Staff ID", appointment.staffId?.take(8)?.plus("…") ?: "—")
                DetailRow("Patient ID", appointment.patientId?.take(8)?.plus("…") ?: "—")
            }

            // Actions
            val isActive = appointment.status.uppercase() in listOf("SCHEDULED", "CONFIRMED")
            if (isActive) {
                Spacer(Modifier.height(8.dp))

                if (onCancel != null) {
                    Button(
                        onClick = { showCancelDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                    ) {
                        Icon(Icons.Default.Cancel, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel Appointment")
                    }
                }
            }
        }
    }

    // Cancel dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = ErrorRed) },
            title = { Text("Cancel Appointment") },
            text = {
                Column {
                    Text("Are you sure you want to cancel this appointment?")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = { cancelReason = it },
                        label = { Text("Reason (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCancel?.invoke(cancelReason)
                        showCancelDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) { Text("Cancel Appointment") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCancelDialog = false }) { Text("Keep") }
            }
        )
    }
}

// ── Detail Card ───────────────────────────────────────────────────────────────

@Composable
fun DetailCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = BrandBlue)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
