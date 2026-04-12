package com.bitnesttechs.hms.patient.features.medications

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
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationsScreen(onBack: () -> Unit = {}, viewModel: MedicationsViewModel = hiltViewModel()) {
    val medications by viewModel.medications.collectAsState()
    val prescriptions by viewModel.prescriptions.collectAsState()
    val refills by viewModel.refills.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Medications", "Prescriptions", "Refills")
    var selectedMed by remember { mutableStateOf<MedicationDto?>(null) }
    var selectedRx by remember { mutableStateOf<PrescriptionDto?>(null) }
    var refillTarget by remember { mutableStateOf<PrescriptionDto?>(null) }
    val snackbarMessage by viewModel.snackbar.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { snackbarHostState.showSnackbar(it); viewModel.clearSnackbar() }
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
                            Text("No active medications")
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
                                            color = if (med.isActive) androidx.compose.ui.graphics.Color(0xFF2E7D32).copy(alpha = 0.12f)
                                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)) {
                                            Text(if (med.isActive) "Active" else "Inactive",
                                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (med.isActive) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Medium)
                                        }
                                    }
                                    med.dosage?.let { dosage ->
                                        val dosageFreq = listOfNotNull(dosage, med.frequency).joinToString(" · ")
                                        Text(dosageFreq, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    med.prescribedBy?.let { Text("Prescribed by $it", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    med.startDate?.let { Text("Since ${it.take(10)}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                            Text("No prescriptions")
                        }
                    }
                    items(prescriptions) { rx ->
                        var expanded by remember { mutableStateOf(false) }
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(2.dp),
                            modifier = Modifier.clickable { selectedRx = rx }
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(rx.medicationName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f))
                                    Surface(shape = RoundedCornerShape(50), color = BrandBlue.copy(alpha = 0.1f)) {
                                        Text(rx.status.replaceFirstChar { it.uppercase() },
                                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall, color = BrandBlue,
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
                                rx.prescribedBy?.let { Text("By $it", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("${rx.refillsRemaining} refill(s) remaining",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (rx.refillsRemaining > 0) {
                                        FilledTonalButton(
                                            onClick = { refillTarget = rx },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Icon(Icons.Default.Medication, null, Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Request Refill", style = MaterialTheme.typography.labelSmall)
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
                            Text("No refill requests on record")
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
                                        color = refillStatusColor(refill.status).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            refill.status.replace("_", " ")
                                                .replaceFirstChar { it.uppercase() },
                                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = refillStatusColor(refill.status)
                                        )
                                    }
                                }
                                refill.preferredPharmacy?.takeIf { it.isNotBlank() }?.let {
                                    Text("Pharmacy: $it", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                refill.requestedAt?.let {
                                    Text("Requested: ${it.take(10)}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    // Refill Request Dialog
    refillTarget?.let { rx ->
        var pharmacy by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { refillTarget = null },
            title = { Text("Request Refill", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(rx.medicationName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                MedDetailRow("Status", if (med.isActive) "Active" else "Inactive")
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
        title = { Text(rx.medicationName, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rx.status.takeIf { it.isNotBlank() }?.let { MedDetailRow("Status", it) }
                HorizontalDivider()

                Text("Dosage & Administration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                rx.dosage?.let { MedDetailRow("Dosage", it) }
                rx.frequency?.let { MedDetailRow("Frequency", it) }
                rx.quantity?.let { MedDetailRow("Quantity", "$it") }
                HorizontalDivider()

                Text("Refills", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                MedDetailRow("Remaining", "${rx.refillsRemaining}")
                HorizontalDivider()

                Text("Dates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                rx.prescribedDate?.let { MedDetailRow("Prescribed", it.take(10)) }
                rx.expiryDate?.let { MedDetailRow("Expires", it.take(10)) }

                rx.prescribedBy?.let {
                    HorizontalDivider()
                    MedDetailRow("Prescribed By", it)
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

@Composable
private fun MedDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun refillStatusColor(status: String): androidx.compose.ui.graphics.Color {
    return when (status.uppercase()) {
        "COMPLETED", "FILLED", "DISPENSED" -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
        "APPROVED" -> androidx.compose.ui.graphics.Color(0xFF1565C0)
        "PENDING", "REQUESTED" -> androidx.compose.ui.graphics.Color(0xFFF9A825)
        "CANCELLED", "DENIED" -> androidx.compose.ui.graphics.Color(0xFFC62828)
        else -> androidx.compose.ui.graphics.Color.Gray
    }
}
