package com.bitnesttechs.hms.patient.features.appointments

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.AppointmentDto
import com.bitnesttechs.hms.patient.ui.theme.*
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentDetailScreen(
    appointment: AppointmentDto,
    onBack: () -> Unit,
    onCancel: ((String) -> Unit)? = null,
    /** (appointmentId, newDate as yyyy-MM-dd, newStartTime as HH:mm) */
    onReschedule: ((String, String, String) -> Unit)? = null
) {
    var showCancelDialog by remember { mutableStateOf(false) }
    var cancelReason by remember { mutableStateOf("") }
    var showRescheduleSheet by remember { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.appointment_details)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f))
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            stringResource(R.string.appointment_ref, appointment.id.take(8)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            DetailCard(title = stringResource(R.string.provider), icon = Icons.Default.Person) {
                DetailRow(stringResource(R.string.doctor_label), appointment.staffName ?: "—")
                DetailRow(stringResource(R.string.department), appointment.departmentName ?: "—")
                DetailRow(stringResource(R.string.hospital), appointment.hospitalName ?: "—")
            }

            DetailCard(title = stringResource(R.string.schedule), icon = Icons.Default.CalendarMonth) {
                DetailRow(stringResource(R.string.date), appointment.appointmentDate)
                DetailRow(stringResource(R.string.time), appointment.timeDisplay ?: "—")
            }

            if (!appointment.reason.isNullOrBlank() || !appointment.notes.isNullOrBlank()) {
                DetailCard(title = stringResource(R.string.visit_details), icon = Icons.Default.Description) {
                    if (!appointment.reason.isNullOrBlank()) {
                        DetailRow(stringResource(R.string.reason), appointment.reason)
                    }
                    if (!appointment.notes.isNullOrBlank()) {
                        DetailRow(stringResource(R.string.notes), appointment.notes)
                    }
                }
            }

            DetailCard(title = stringResource(R.string.record_info), icon = Icons.Default.Info) {
                DetailRow(stringResource(R.string.reference), "APPT-${appointment.id.take(8).uppercase()}")
                DetailRow(stringResource(R.string.provider), appointment.staffName ?: "—")
                DetailRow(stringResource(R.string.location), appointment.hospitalName ?: "—")
            }

            val isActive = appointment.status.uppercase() in listOf("SCHEDULED", "CONFIRMED")
            if (isActive) {
                Spacer(Modifier.height(8.dp))

                if (onReschedule != null) {
                    OutlinedButton(
                        onClick = { showRescheduleSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.EditCalendar, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.reschedule_appointment))
                    }
                }

                if (onCancel != null) {
                    Button(
                        onClick = { showCancelDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                    ) {
                        Icon(Icons.Default.Cancel, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cancel_appointment))
                    }
                }
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = ErrorRed) },
            title = { Text(stringResource(R.string.cancel_appointment)) },
            text = {
                Column {
                    Text(stringResource(R.string.cancel_appointment_confirm))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = { cancelReason = it },
                        label = { Text(stringResource(R.string.reason_optional_label)) },
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
                ) { Text(stringResource(R.string.cancel_appointment)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCancelDialog = false }) { Text(stringResource(R.string.keep)) }
            }
        )
    }

    if (showRescheduleSheet && onReschedule != null) {
        RescheduleSheet(
            onDismiss = { showRescheduleSheet = false },
            onConfirm = { newDate, newTime ->
                showRescheduleSheet = false
                onReschedule(appointment.id, newDate, newTime)
            }
        )
    }
}

/**
 * Same date and time pickers as the booking sheet. The backend takes the
 * new date and start time and derives the end from the slot length.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RescheduleSheet(
    onDismiss: () -> Unit,
    onConfirm: (newDate: String, newStartTime: String) -> Unit
) {
    var date by remember { mutableStateOf("") }
    var hour by remember { mutableIntStateOf(9) }
    var minute by remember { mutableIntStateOf(0) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val displayTime = remember(hour, minute) {
        LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault()))
    }
    val apiTime = remember(hour, minute) { String.format(Locale.US, "%02d:%02d", hour, minute) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.reschedule_appointment), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.reschedule_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = date,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.new_date)) },
                placeholder = { Text(stringResource(R.string.select_date)) },
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.CalendarMonth, stringResource(R.string.pick_date))
                    }
                },
                modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }
            )
            OutlinedTextField(
                value = displayTime,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.new_time)) },
                trailingIcon = {
                    IconButton(onClick = { showTimePicker = true }) {
                        Icon(Icons.Default.Schedule, stringResource(R.string.pick_time))
                    }
                },
                modifier = Modifier.fillMaxWidth().clickable { showTimePicker = true }
            )

            Button(
                onClick = { onConfirm(date, apiTime) },
                enabled = date.length >= 10,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) { Text(stringResource(R.string.reschedule), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= System.currentTimeMillis() - 86_400_000
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) { DatePicker(state = state) }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.select_time_title)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    hour = state.hour
                    minute = state.minute
                    showTimePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

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
