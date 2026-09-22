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
import com.bitnesttechs.hms.patient.ui.theme.SuccessGreen
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
    /** (appointmentId, newDate as yyyy-MM-dd, newStartTime as HH:mm, newEndTime as HH:mm) */
    onReschedule: ((String, String, String, String) -> Unit)? = null,
    onPreCheckIn: (() -> Unit)? = null
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

            // RESCHEDULED is what the backend sets after a move; the patient can
            // still move or cancel it, so it must keep both actions.
            val isActive = appointment.status.uppercase() in listOf("SCHEDULED", "CONFIRMED", "RESCHEDULED")
            if (isActive) {
                Spacer(Modifier.height(8.dp))

                // Pre-check-in, as on the web: offered on SCHEDULED/CONFIRMED visits
                // not yet checked in. The server accepts it from seven days before
                // the visit up to the day itself, so outside that window the button
                // waits rather than sending a request that can only be refused.
                if (appointment.preCheckedIn == true) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pre_checkin_done), color = SuccessGreen, fontWeight = FontWeight.Medium)
                    }
                } else if (onPreCheckIn != null && appointment.status.uppercase() in listOf("SCHEDULED", "CONFIRMED")) {
                    val daysUntil = runCatching {
                        java.time.temporal.ChronoUnit.DAYS.between(
                            java.time.LocalDate.now(), java.time.LocalDate.parse(appointment.appointmentDate.take(10))
                        )
                    }.getOrNull()
                    val inWindow = daysUntil != null && daysUntil in 0..PRE_CHECKIN_WINDOW_DAYS
                    OutlinedButton(onClick = onPreCheckIn, enabled = inWindow, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FactCheck, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pre_checkin_online))
                    }
                    if (!inWindow) {
                        // Before the window it has not opened; a visit already dated in the past has closed.
                        val closed = daysUntil != null && daysUntil < 0
                        Text(
                            if (closed) stringResource(R.string.pre_checkin_closed)
                            else stringResource(R.string.pre_checkin_window, PRE_CHECKIN_WINDOW_DAYS),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

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
            durationMinutes = appointment.durationMinutes,
            onDismiss = { showRescheduleSheet = false },
            onConfirm = { newDate, newStart, newEnd ->
                showRescheduleSheet = false
                onReschedule(appointment.id, newDate, newStart, newEnd)
            }
        )
    }
}

/**
 * Same date and time pickers as the booking sheet. The backend requires an
 * end time as well as a start, so the appointment keeps its current length
 * (30 minutes when the record carries no times, as on iOS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RescheduleSheet(
    durationMinutes: Long,
    onDismiss: () -> Unit,
    onConfirm: (newDate: String, newStartTime: String, newEndTime: String) -> Unit
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
    val endTime = remember(hour, minute, durationMinutes) { LocalTime.of(hour, minute).plusMinutes(durationMinutes) }
    val apiEndTime = remember(endTime) { endTime.format(DateTimeFormatter.ofPattern("HH:mm")) }
    // LocalTime wraps past midnight, and the backend rejects an end that
    // does not follow the start; a slot that would cross midnight is refused here.
    val sameDay = remember(hour, minute, endTime) { endTime.isAfter(LocalTime.of(hour, minute)) }

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
                onClick = { onConfirm(date, apiTime, apiEndTime) },
                enabled = date.length >= 10 && sameDay,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) { Text(stringResource(R.string.reschedule), fontWeight = FontWeight.SemiBold) }
            if (!sameDay) {
                Text(stringResource(R.string.reschedule_crosses_midnight),
                    style = MaterialTheme.typography.bodySmall, color = ErrorRed)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(selectableDates = TodayOrLater)
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

/**
 * The picker hands back UTC midnight of the chosen day; comparing it with
 * today's UTC midnight admits today and nothing earlier. The previous
 * "now minus 24 hours" window let yesterday through for most of the day,
 * and the backend's @FutureOrPresent then answered 400.
 */
@OptIn(ExperimentalMaterial3Api::class)
object TodayOrLater : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        // The picker's days are calendar days at UTC midnight; the floor is the
        // patient's LOCAL today expressed the same way, so that east of UTC just
        // after midnight yesterday is not still on offer.
        val todayLocalAsUtc = java.time.LocalDate.now().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        return utcTimeMillis >= todayLocalAsUtc
    }
}

/** The server's pre-check-in window: from this many days before the visit to the day itself. */
private const val PRE_CHECKIN_WINDOW_DAYS = 7L

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
