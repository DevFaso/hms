package com.bitnesttechs.hms.patient.features.appointments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.BookAppointmentRequest
import com.bitnesttechs.hms.patient.features.dashboard.StatusBadge
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.SuccessGreen
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The backend defaults the slot to 30 minutes; the wizard mirrors that so the same-day rule can be checked here. */
private const val DEFAULT_SLOT_MINUTES = 30L
private const val REASON_MAX = 500
private const val NOTES_MAX = 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentsScreen(
    navController: NavController? = null,
    onMenuClick: () -> Unit = {},
    viewModel: AppointmentsViewModel = hiltViewModel()
) {
    val appointments by viewModel.appointments.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val actionResult by viewModel.actionResult.collectAsState()
    val bookingSheetOpen by viewModel.bookingSheetOpen.collectAsState()
    var cancelDialogId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Snackbar: shown from a child coroutine so a second outcome is never
    // waiting on the first one's dismissal.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionResult) {
        actionResult?.let { outcome ->
            val text = listOfNotNull(context.getString(outcome.resId), outcome.detail).joinToString(": ")
            viewModel.clearActionResult()
            scope.launch { snackbarHostState.showSnackbar(text) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appointments)) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu), tint = Color.White)
                    }
                },
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
                onClick = { viewModel.showBooking() },
                containerColor = BrandBlue
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.book_appointment), tint = Color.White)
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }
        if (loadError) {
            // A failed load is not an empty diary.
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.CloudOff, null, Modifier.size(64.dp), tint = ErrorRed)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.appointments_load_failed), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { viewModel.load() }) { Text(stringResource(R.string.retry)) }
                }
            }
            return@Scaffold
        }
        if (appointments.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.no_appointments_found), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { viewModel.showBooking() }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.book_appointment))
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // Store the appointment in savedStateHandle and navigate
                            navController?.currentBackStackEntry?.savedStateHandle?.set("appointment", appt)
                            navController?.navigate("appointment_detail")
                        },
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
                                    appt.staffName ?: stringResource(R.string.unknown_doctor),
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
                                    Text(stringResource(R.string.reason_with_value, it), style = MaterialTheme.typography.bodySmall)
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
                                    Text(stringResource(R.string.cancel))
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
            title = { Text(stringResource(R.string.cancel_appointment)) },
            text = { Text(stringResource(R.string.cancel_appointment_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.cancelAppointment(id, "Patient cancelled"); cancelDialogId = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
                ) { Text(stringResource(R.string.cancel_appointment)) }
            },
            dismissButton = {
                TextButton(onClick = { cancelDialogId = null }) { Text(stringResource(R.string.keep)) }
            }
        )
    }

    // Booking bottom sheet
    if (bookingSheetOpen) {
        BookAppointmentSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.hideBooking() }
        )
    }
}

/**
 * The web's booking wizard: hospital (where the patient is registered) ->
 * department -> provider (optional, "any available" by default) -> date and
 * start time -> reason and notes. A first-time patient with no visit history
 * can book from here; the old sheet only listed doctors from past
 * appointments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookAppointmentSheet(
    viewModel: AppointmentsViewModel,
    onDismiss: () -> Unit
) {
    val options by viewModel.bookingOptions.collectAsState()
    val context = LocalContext.current
    // Saveable, like the sheet's open flag in the view model: a rotation keeps
    // the wizard where the patient left it, request in flight included.
    var hospitalId by rememberSaveable { mutableStateOf<String?>(null) }
    var departmentId by rememberSaveable { mutableStateOf<String?>(null) }
    var staffId by rememberSaveable { mutableStateOf<String?>(null) }   // null = any available provider
    var dateIso by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedHour by rememberSaveable { mutableIntStateOf(9) }
    var selectedMinute by rememberSaveable { mutableIntStateOf(0) }
    var reason by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val date = dateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    // Bumped by a Book tap so the "already passed" guard is re-read at tap time,
    // not at the last recomposition (a field left alone for minutes never recomposes).
    var tapClock by remember { mutableIntStateOf(0) }

    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val startTime = LocalTime.of(selectedHour, selectedMinute)
    val displayTime = remember(startTime) {
        startTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
    }
    val displayDate = remember(date) {
        date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())) ?: ""
    }
    // The server ends the slot 30 minutes after the start on the SAME date; a
    // start after 23:30 would end before it began and be refused.
    val crossesMidnight = startTime.plusMinutes(DEFAULT_SLOT_MINUTES) <= startTime
    // The server only requires the date to be today or later, so today with a
    // start already gone by would book a visit in the past.
    val timeHasPassed = remember(date, startTime, tapClock) { hasPassed(date, startTime) }
    val timeError = when {
        crossesMidnight -> stringResource(R.string.reschedule_crosses_midnight)
        timeHasPassed -> stringResource(R.string.time_already_passed)
        else -> null
    }

    // While the request is out, the sheet stays: a swipe, a scrim tap or back
    // would otherwise let the patient reopen and submit the same visit twice.
    // Back is the sheet's own dialog window's, not the activity's, so it is
    // refused through the sheet properties rather than a BackHandler. The
    // lambda is one remembered instance reading the latest flag: Material3
    // keys the sheet state on it, and a fresh lambda per recomposition would
    // rebuild the state at Hidden and drop the sheet on every list load.
    val isBooking by rememberUpdatedState(options.isBooking)
    val keepWhileBooking = remember { { _: SheetValue -> !isBooking } }
    val sheetState = rememberModalBottomSheetState(confirmValueChange = keepWhileBooking)

    val hospital = options.hospitals.find { it.id == hospitalId }
    val department = options.departments.find { it.id == departmentId }
    val provider = options.providers.find { it.id == staffId }
    val canSubmit = hospitalId != null && departmentId != null && date != null &&
        options.providersLoaded && options.providers.isNotEmpty() &&
        timeError == null && !options.isBooking &&
        reason.length <= REASON_MAX && notes.length <= NOTES_MAX

    ModalBottomSheet(
        onDismissRequest = { if (!options.isBooking) onDismiss() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !options.isBooking)
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.book_appointment), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            when {
                options.loadError != null -> {
                    // A failed list is an error with a retry for that step, never a "no hospitals".
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.booking_options_failed), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = {
                                when (options.loadError) {
                                    BookingStep.HOSPITALS -> viewModel.loadHospitals()
                                    BookingStep.DEPARTMENTS -> hospitalId?.let { viewModel.selectHospital(it) }
                                    BookingStep.PROVIDERS -> if (hospitalId != null && departmentId != null) {
                                        viewModel.selectDepartment(hospitalId!!, departmentId!!)
                                    }
                                    null -> Unit
                                }
                            }) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
                options.loading == BookingStep.HOSPITALS -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                options.hospitalsLoaded && options.hospitals.isEmpty() -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            stringResource(R.string.no_hospitals_for_booking),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (options.hospitalsLoaded && options.hospitals.isNotEmpty()) {
                // 1. Hospital
                BookingDropdown(
                    label = stringResource(R.string.hospital),
                    value = hospital?.name ?: "",
                    placeholder = stringResource(R.string.select_hospital),
                    enabled = true,
                    items = options.hospitals.map { it.id to (it.name ?: it.id) },
                    subtitles = options.hospitals.associate { it.id to it.address },
                    onSelect = { id ->
                        if (id != hospitalId) {
                            hospitalId = id
                            departmentId = null
                            staffId = null
                            id?.let { viewModel.selectHospital(it) }
                        }
                    }
                )

                // 2. Department
                BookingDropdown(
                    label = stringResource(R.string.department),
                    value = department?.name ?: "",
                    placeholder = stringResource(R.string.select_department),
                    enabled = options.departmentsLoaded && options.departments.isNotEmpty(),
                    loading = options.loading == BookingStep.DEPARTMENTS,
                    items = options.departments.map { it.id to (it.name ?: it.id) },
                    supporting = if (options.departmentsLoaded && options.departments.isEmpty())
                        stringResource(R.string.no_departments_for_booking) else null,
                    onSelect = { id ->
                        if (id != departmentId) {
                            departmentId = id
                            staffId = null
                            if (id != null && hospitalId != null) viewModel.selectDepartment(hospitalId!!, id)
                        }
                    }
                )

                // 3. Provider, optional: the server assigns one when none is chosen.
                BookingDropdown(
                    label = stringResource(R.string.provider_optional),
                    value = provider?.displayName
                        ?: if (options.providersLoaded && options.providers.isNotEmpty()) stringResource(R.string.any_provider) else "",
                    placeholder = stringResource(R.string.any_provider),
                    enabled = options.providersLoaded && options.providers.isNotEmpty(),
                    loading = options.loading == BookingStep.PROVIDERS,
                    items = listOf<Pair<String?, String>>(null to stringResource(R.string.any_provider)) +
                        options.providers.map { it.id to it.displayName },
                    supporting = if (options.providersLoaded && options.providers.isEmpty())
                        stringResource(R.string.no_providers_for_booking) else null,
                    onSelect = { id -> staffId = id; viewModel.clearBookingError() }
                )

                // 4. Date and start time
                OutlinedTextField(
                    value = displayDate,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.appointment_date)) },
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
                    isError = timeError != null,
                    supportingText = timeError?.let { { Text(it) } },
                    label = { Text(stringResource(R.string.start_time)) },
                    trailingIcon = {
                        IconButton(onClick = { showTimePicker = true }) {
                            Icon(Icons.Default.Schedule, stringResource(R.string.pick_time))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clickable { showTimePicker = true }
                )

                // 5. Reason and notes, with the server's caps
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.reason_for_visit)) },
                    placeholder = { Text(stringResource(R.string.reason_hint)) },
                    isError = reason.length > REASON_MAX,
                    supportingText = { Text("${reason.length}/$REASON_MAX") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.additional_notes)) },
                    placeholder = { Text(stringResource(R.string.notes_hint)) },
                    isError = notes.length > NOTES_MAX,
                    supportingText = { Text("${notes.length}/$NOTES_MAX") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                options.bookingError?.let { err ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            listOfNotNull(stringResource(err.resId), err.detail).joinToString(": "),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Button(
                    onClick = {
                        val h = hospitalId ?: return@Button
                        val d = departmentId ?: return@Button
                        val day = date ?: return@Button
                        if (hasPassed(day, startTime)) { tapClock++; return@Button }
                        viewModel.book(
                            BookAppointmentRequest(
                                hospitalId = h,
                                departmentId = d,
                                staffId = staffId,
                                date = day.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                startTime = String.format(Locale.US, "%02d:%02d", selectedHour, selectedMinute),
                                reason = reason.trim().ifBlank { null },
                                notes = notes.trim().ifBlank { null }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = canSubmit,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    if (options.isBooking) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.scheduling), fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(stringResource(R.string.book_appointment), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        // Same guard as the reschedule sheet: today or later, by calendar day.
        val datePickerState = rememberDatePickerState(selectableDates = TodayOrLater)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val instant = java.time.Instant.ofEpochMilli(millis)
                        dateIso = instant.atZone(java.time.ZoneId.of("UTC")).toLocalDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                        viewModel.clearBookingError()
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Time picker dialog
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = selectedHour,
            initialMinute = selectedMinute,
            is24Hour = is24Hour
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.select_time_title)) },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    selectedHour = timePickerState.hour
                    selectedMinute = timePickerState.minute
                    viewModel.clearBookingError()
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
 * A start already gone by: today with a time not after now, or a date that
 * became yesterday while the sheet stayed open. The server only checks the
 * date, so this is the only guard.
 */
private fun hasPassed(date: LocalDate?, startTime: LocalTime): Boolean {
    if (date == null) return false
    val today = LocalDate.now()
    return date.isBefore(today) || (date == today && !startTime.isAfter(LocalTime.now()))
}

/** One level of the wizard: a read-only field opening a menu of (id, label) rows, with an optional subtitle per id. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookingDropdown(
    label: String,
    value: String,
    placeholder: String,
    enabled: Boolean,
    items: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
    loading: Boolean = false,
    subtitles: Map<String, String?> = emptyMap(),
    supporting: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled || loading,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            supportingText = supporting?.let { { Text(it) } },
            trailingIcon = {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled)
            },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            items.forEach { (id, text) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(text, fontWeight = FontWeight.Medium)
                            id?.let { subtitles[it] }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    }
                )
            }
        }
    }
}
