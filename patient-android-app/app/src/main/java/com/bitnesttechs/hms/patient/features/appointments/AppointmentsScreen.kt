package com.bitnesttechs.hms.patient.features.appointments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.BookAppointmentRequest
import com.bitnesttechs.hms.patient.features.dashboard.StatusBadge
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.SuccessGreen
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentsScreen(viewModel: AppointmentsViewModel = hiltViewModel()) {
    val appointments by viewModel.appointments.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val bookingResult by viewModel.bookingResult.collectAsState()
    val actionResult by viewModel.actionResult.collectAsState()
    var showBookingSheet by remember { mutableStateOf(false) }
    var cancelDialogId by remember { mutableStateOf<String?>(null) }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(bookingResult) {
        bookingResult?.let { snackbarHostState.showSnackbar(it); viewModel.clearBookingResult() }
    }
    LaunchedEffect(actionResult) {
        actionResult?.let { snackbarHostState.showSnackbar(it); viewModel.clearActionResult() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Appointments") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue,
                    titleContentColor = Color.White),
                actions = {
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Default.Refresh, null, tint = Color.White)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showBookingSheet = true },
                containerColor = BrandBlue
            ) {
                Icon(Icons.Default.Add, "Book appointment", tint = Color.White)
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }
        if (appointments.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No appointments found", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { showBookingSheet = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Book Appointment")
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(appointments) { appt ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    appt.staffName ?: "Unknown Doctor",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                appt.departmentName?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${appt.appointmentDate} ${appt.timeDisplay ?: ""}".trim(),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                appt.hospitalName?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                appt.reason?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Reason: $it", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            StatusBadge(
                                text = appt.statusDisplay,
                                color = when (appt.status.uppercase()) {
                                    "SCHEDULED" -> BrandBlue
                                    "COMPLETED" -> SuccessGreen
                                    "CANCELLED" -> ErrorRed
                                    else -> BrandBlue
                                }
                            )
                        }
                        // Cancel button for scheduled appointments
                        if (appt.status.uppercase() == "SCHEDULED") {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(
                                    onClick = { cancelDialogId = appt.id },
                                    colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
                                ) {
                                    Icon(Icons.Default.Cancel, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // Cancel confirmation dialog
    cancelDialogId?.let { id ->
        AlertDialog(
            onDismissRequest = { cancelDialogId = null },
            title = { Text("Cancel Appointment") },
            text = { Text("Are you sure you want to cancel this appointment?") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.cancelAppointment(id, "Patient cancelled"); cancelDialogId = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
                ) { Text("Cancel Appointment") }
            },
            dismissButton = {
                TextButton(onClick = { cancelDialogId = null }) { Text("Keep") }
            }
        )
    }

    // Booking bottom sheet
    if (showBookingSheet) {
        BookAppointmentSheet(
            viewModel = viewModel,
            onDismiss = { showBookingSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookAppointmentSheet(
    viewModel: AppointmentsViewModel,
    onDismiss: () -> Unit
) {
    val doctors = viewModel.doctorOptions
    var selectedDoctorKey by remember { mutableStateOf(doctors.firstOrNull()?.key ?: "") }
    var date by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf("09:00") }
    var reason by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val selectedDoctor = doctors.find { it.key == selectedDoctorKey }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Book Appointment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            if (doctors.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        "No doctors found from previous appointments. Please visit a hospital first.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            } else {
                // Doctor picker
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedDoctor?.let { "${it.staffName} — ${it.hospitalName ?: ""}" } ?: "Select doctor",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Doctor") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        doctors.forEach { doc ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(doc.staffName, fontWeight = FontWeight.Medium)
                                        doc.hospitalName?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                onClick = {
                                    selectedDoctorKey = doc.key
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = date,
                onValueChange = { date = it },
                label = { Text("Date (YYYY-MM-DD)") },
                placeholder = { Text("2026-04-20") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = startTime,
                onValueChange = { startTime = it },
                label = { Text("Start Time (HH:mm)") },
                placeholder = { Text("09:00") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason (optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    selectedDoctor?.let { doc ->
                        viewModel.bookAppointment(
                            BookAppointmentRequest(
                                appointmentDate = date,
                                startTime = startTime,
                                staffId = doc.staffId,
                                staffEmail = doc.staffEmail,
                                hospitalId = doc.hospitalId,
                                departmentId = doc.departmentId,
                                reason = reason.ifBlank { null }
                            )
                        )
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = selectedDoctor != null && date.length >= 10,
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) {
                Text("Book Appointment", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
