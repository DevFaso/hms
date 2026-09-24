package com.bitnesttechs.hms.patient.features.medications

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.MedicationDto
import com.bitnesttechs.hms.patient.core.models.PrescriptionDto
import com.bitnesttechs.hms.patient.core.models.RefillDto
import com.bitnesttechs.hms.patient.core.models.RefillStatus
import androidx.compose.ui.res.stringResource
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.badgeFill
import com.bitnesttechs.hms.patient.ui.theme.onBadge
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationsScreen(onBack: () -> Unit = {}, viewModel: MedicationsViewModel = hiltViewModel()) {
    val medications by viewModel.medications.collectAsState()
    val prescriptions by viewModel.prescriptions.collectAsState()
    val refills by viewModel.refills.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadFailed by viewModel.loadFailed.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Medications", "Prescriptions", "Refills")
    var selectedMed by remember { mutableStateOf<MedicationDto?>(null) }
    var selectedRx by remember { mutableStateOf<PrescriptionDto?>(null) }
    var refillTarget by remember { mutableStateOf<PrescriptionDto?>(null) }
    var cancelRefillTarget by remember { mutableStateOf<RefillDto?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val outcome by viewModel.outcome.collectAsState()
    val outcomeText = outcome?.let { o ->
        val base = stringResource(o.resId)
        o.detail?.let { "$base ($it)" } ?: base
    }
    LaunchedEffect(outcome) {
        outcomeText?.let { snackbarHostState.showSnackbar(it); viewModel.clearOutcome() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Medications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = androidx.compose.ui.graphics.Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { idx, title ->
                    Tab(selected = selectedTab == idx, onClick = { selectedTab = idx },
                        text = { Text(title) })
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandBlue)
                }
                return@Column
            }

            when (selectedTab) {
                0 -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (medications.isEmpty()) item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyOrRetry(R.string.no_active_medications, loadFailed) { viewModel.load() }
                        }
                    }
                    items(medications) { med ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(2.dp),
                            modifier = Modifier.clickable { selectedMed = med }
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text(med.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Surface(shape = RoundedCornerShape(50),
                                            color = med.statusEnum.tone.badgeFill().copy(alpha = 0.15f)) {
                                            Text(stringResource(med.statusEnum.labelRes),
                                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = med.statusEnum.tone.onBadge(),
                                                fontWeight = FontWeight.Medium)
                                        }
                                    }
                                    med.dosage?.let { dosage ->
                                        val dosageFreq = listOfNotNull(dosage, med.frequency).joinToString(" · ")
                                        Text(dosageFreq, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    med.prescribedBy?.let {
                                        Text(stringResource(R.string.prescribed_by_with_value, it),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    med.startDate?.let {
                                        Text(stringResource(R.string.since_with_value, it.take(10)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                1 -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (prescriptions.isEmpty()) item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyOrRetry(R.string.no_prescriptions, loadFailed) { viewModel.load() }
                        }
                    }
                    items(prescriptions) { rx ->
                        var expanded by remember { mutableStateOf(false) }
                        // The backend allows ONE open request per prescription
                        // (PatientPortalServiceImpl.requestMedicationRefill).
                        // Until this change the button never rendered at all, so
                        // that refusal was unreachable; now it is told before the
                        // patient taps rather than after a 400.
                        //
                        // PatientMedicationResponseDTO is built from prescriptions
                        // and its id IS the prescription id, and its
                        // `refillRequestOpen` is computed over EVERY refill row,
                        // not a page — so prefer it. The scan over the loaded
                        // refills page is the fallback for a prescription outside
                        // the medications window; either way the server re-checks.
                        // If the medications row exists it DECIDES, including
                        // when it says there is no open request. The two sources
                        // go stale in opposite directions: falling through on a
                        // `false` would let a stale refills page — kept by
                        // `load()` when only that fetch failed — hide the button
                        // for a refill the patient has just cancelled, whereas
                        // the opposite staleness merely costs a 400 they are
                        // then told about in their own language. This is the
                        // safer way to be wrong.
                        val medicationRow = medications.firstOrNull { it.id == rx.id }
                        val openRefill = if (medicationRow != null) {
                            medicationRow.openRefillStatus
                        } else {
                            refills.firstOrNull { it.prescriptionId == rx.id && it.statusEnum.isOpen }
                                ?.statusEnum
                        }
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(2.dp),
                            modifier = Modifier.clickable { selectedRx = rx }
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(rx.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f))
                                    Surface(shape = RoundedCornerShape(50),
                                        color = rx.statusEnum.tone.badgeFill().copy(alpha = 0.15f)) {
                                        Text(stringResource(rx.statusEnum.labelRes),
                                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = rx.statusEnum.tone.onBadge(),
                                            fontWeight = FontWeight.Medium)
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = "View details",
                                        modifier = Modifier.padding(start = 4.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                rx.dosage?.let { dosage ->
                                    val dosageFreq = listOfNotNull(dosage, rx.frequency).joinToString(" · ")
                                    Text(dosageFreq, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                rx.staffFullName?.takeIf { it.isNotBlank() }?.let {
                                    Text(stringResource(R.string.prescribed_by_with_value, it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // Where the order went, when it left the hospital's own
                                // dispensary (PrescriptionResponseDTO.pharmacyName).
                                rx.pharmacyName?.takeIf { it.isNotBlank() }?.let {
                                    Text(stringResource(R.string.rx_pharmacy_with_value, it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(6.dp))
                                // The backend gate is PrescriptionStatus.isRefillable(),
                                // not a refill counter — this DTO has never carried one.
                                if (rx.statusEnum.isRefillable) {
                                    if (openRefill != null) {
                                        // REQUESTED is "awaiting review"; PAUSED is
                                        // "your care team held it and will follow
                                        // up" — the one message that explains the
                                        // delay, so do not collapse the two.
                                        Text(stringResource(openRefillMessage(openRefill)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            FilledTonalButton(
                                                onClick = { refillTarget = rx },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Icon(Icons.Default.Medication, null, Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(stringResource(R.string.request_refill), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (refills.isEmpty()) item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyOrRetry(R.string.no_refills, loadFailed) { viewModel.load() }
                        }
                    }
                    items(refills) { refill ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        refill.medicationName ?: "Refill",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = refill.statusEnum.tone.badgeFill().copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            stringResource(refill.statusEnum.labelRes),
                                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = refill.statusEnum.tone.onBadge()
                                        )
                                    }
                                }
                                refill.preferredPharmacy?.takeIf { it.isNotBlank() }?.let {
                                    Text(stringResource(R.string.rx_pharmacy_with_value, it),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                refill.requestedAt?.let {
                                    Text(stringResource(R.string.refill_requested_with_value, it.take(10)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (refill.statusEnum == RefillStatus.REQUESTED) {
                                    Text(stringResource(R.string.refill_sent_for_review),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // REQUESTED and PAUSED are the two states the backend
                                // (cancelMyRefill) and the web let the patient withdraw.
                                if (refill.statusEnum.isCancellable) {
                                    TextButton(
                                        onClick = { cancelRefillTarget = refill },
                                        colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
                                    ) { Text(stringResource(R.string.cancel_refill_request)) }
                                }
                                refill.updatedAt?.takeIf { it != refill.requestedAt }?.let {
                                    Text("Updated: ${it.take(10)}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                refill.providerNotes?.takeIf { it.isNotBlank() }?.let {
                                    Text("Provider: $it", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                refill.notes?.takeIf { it.isNotBlank() }?.let {
                                    Text("Notes: $it", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Medication Detail Dialog
    selectedMed?.let { med ->
        MedicationDetailDialog(med = med, onDismiss = { selectedMed = null })
    }

    // Prescription Detail Dialog
    selectedRx?.let { rx ->
        PrescriptionDetailDialog(rx = rx, onDismiss = { selectedRx = null })
    }

    cancelRefillTarget?.let { refill ->
        AlertDialog(
            onDismissRequest = { cancelRefillTarget = null },
            title = { Text(stringResource(R.string.cancel_refill_request), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.cancel_refill_confirm, refill.medicationName ?: "")) },
            confirmButton = {
                Button(
                    onClick = { viewModel.cancelRefill(refill.id); cancelRefillTarget = null },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) { Text(stringResource(R.string.cancel_refill_request)) }
            },
            dismissButton = {
                TextButton(onClick = { cancelRefillTarget = null }) { Text(stringResource(R.string.keep)) }
            }
        )
    }

    // Refill Request Dialog
    refillTarget?.let { rx ->
        var pharmacy by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { refillTarget = null },
            title = { Text("Request Refill", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(rx.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    rx.dosage?.let { d ->
                        val info = listOfNotNull(d, rx.frequency).joinToString(" · ")
                        Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    OutlinedTextField(
                        value = pharmacy,
                        onValueChange = { pharmacy = it },
                        label = { Text("Preferred Pharmacy") },
                        placeholder = { Text("e.g. CVS Main St") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes (optional)") },
                        placeholder = { Text("Any special instructions") },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    viewModel.requestRefill(
                        prescriptionId = rx.id,
                        pharmacy = pharmacy.takeIf { it.isNotBlank() },
                        notes = notes.takeIf { it.isNotBlank() }
                    )
                    refillTarget = null
                }) { Text("Submit") }
            },
            dismissButton = {
                TextButton(onClick = { refillTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MedicationDetailDialog(med: MedicationDto, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(med.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status
                MedDetailRow(stringResource(R.string.status), stringResource(med.statusEnum.labelRes))
                HorizontalDivider()

                Text("Dosage & Administration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                med.dosage?.let { MedDetailRow("Dosage", it) }
                med.frequency?.let { MedDetailRow("Frequency", it) }
                med.route?.let { MedDetailRow("Route", it) }
                HorizontalDivider()

                Text("Dates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                med.startDate?.let { MedDetailRow("Start Date", it.take(10)) }
                med.endDate?.let { MedDetailRow("End Date", it.take(10)) }

                med.prescribedBy?.let {
                    HorizontalDivider()
                    MedDetailRow("Prescribed By", it)
                }

                med.instructions?.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    Text("Instructions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

@Composable
private fun PrescriptionDetailDialog(rx: PrescriptionDto, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(rx.displayName, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MedDetailRow(stringResource(R.string.status), stringResource(rx.statusEnum.labelRes))
                HorizontalDivider()

                Text("Dosage & Administration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                rx.dosage?.let { MedDetailRow(stringResource(R.string.dosage), it) }
                rx.frequency?.let { MedDetailRow(stringResource(R.string.frequency), it) }
                rx.duration?.let { MedDetailRow(stringResource(R.string.duration), it) }
                rx.route?.let { MedDetailRow(stringResource(R.string.route), it) }
                HorizontalDivider()

                Text(stringResource(R.string.dates_section),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                rx.createdAt?.let { MedDetailRow(stringResource(R.string.prescribed_at), it.take(10)) }

                rx.staffFullName?.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    MedDetailRow(stringResource(R.string.prescribed_by), it)
                }

                rx.pharmacyName?.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    MedDetailRow(stringResource(R.string.pharmacy), it)
                }

                rx.instructions?.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    Text("Instructions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

/**
 * An empty tab is not the same thing as a tab that could not be loaded. The
 * screen has no pull-to-refresh, so without this a single failed load left
 * the patient's medication list empty for the life of the ViewModel.
 */
@Composable
private fun EmptyOrRetry(@StringRes emptyText: Int, failed: Boolean, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(if (failed) R.string.load_failed else emptyText))
        if (failed) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/**
 * The backend says two different things about an open refill
 * (PatientPortalServiceImpl.requestMedicationRefill): REQUESTED is awaiting
 * review, PAUSED was deliberately held with a follow-up promised.
 */
@StringRes
internal fun openRefillMessage(status: RefillStatus): Int = when (status) {
    RefillStatus.PAUSED -> R.string.refill_on_hold
    RefillStatus.REQUESTED, RefillStatus.APPROVED, RefillStatus.DENIED,
    RefillStatus.DISPENSED, RefillStatus.CANCELLED, RefillStatus.UNKNOWN ->
        R.string.refill_already_open
}

@Composable
private fun MedDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
