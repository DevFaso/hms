package com.bitnesttechs.hms.patient.features.visits

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
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.DischargeSummaryDto
import com.bitnesttechs.hms.patient.core.models.EncounterDto
import com.bitnesttechs.hms.patient.core.models.FollowUpAppointmentDto
import com.bitnesttechs.hms.patient.core.models.MedicationReconciliationDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue

private fun formatDate(iso: String): String {
    return try {
        val parts = iso.take(10).split("-")
        if (parts.size == 3) {
            val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val month = months[parts[1].toInt() - 1]
            val day = parts[2].toInt()
            val year = parts[0]
            "$month $day, $year"
        } else iso.take(10)
    } catch (_: Exception) { iso.take(10) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitHistoryScreen(onBack: () -> Unit = {}, viewModel: VisitHistoryViewModel = hiltViewModel()) {
    val encounters by viewModel.encounters.collectAsState()
    val summaries by viewModel.summaries.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedEncounter by remember { mutableStateOf<EncounterDto?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Visits", "Summaries")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visit History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandBlue, titleContentColor = Color.White
                )
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
                0 -> {
                    if (encounters.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No visits on record", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(encounters) { encounter ->
                                EncounterRow(encounter) { selectedEncounter = encounter }
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
                1 -> {
                    if (summaries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No after-visit summaries available", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(summaries) { summary ->
                                SummaryCard(summary)
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }
    }

    selectedEncounter?.let { enc ->
        val summary = viewModel.summaryForEncounter(enc.id)
        EncounterDetailSheet(enc, summary) { selectedEncounter = null }
    }
}

@Composable
private fun EncounterRow(encounter: EncounterDto, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(10.dp), color = BrandLightBlue,
                modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.LocalHospital, null, tint = BrandBlue, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Type chip like iOS
                    Surface(shape = RoundedCornerShape(4.dp), color = BrandBlue.copy(alpha = 0.1f)) {
                        Text(encounter.encounterType.replace("_", " ").replaceFirstChar { it.uppercase() },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = BrandBlue)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(encounter.doctorName ?: encounter.department ?: "Provider",
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                encounter.department?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                encounter.chiefComplaint?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Text(formatDate(encounter.encounterDate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                EncounterStatusChip(encounter.status)
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EncounterStatusChip(status: String) {
    val (bg, fg) = when (status.uppercase()) {
        "COMPLETED" -> Pair(Color(0xFFDCFCE7), Color(0xFF166534))
        "IN_PROGRESS" -> Pair(Color(0xFFDBEAFE), BrandBlue)
        "CANCELLED" -> Pair(Color(0xFFFEE2E2), Color(0xFFDC2626))
        else -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(20.dp), color = bg) {
        Text(status.replace("_", " "), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EncounterDetailSheet(
    encounter: EncounterDto,
    summary: DischargeSummaryDto?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Visit Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            DetailRow("Type", encounter.encounterType.replace("_", " ").replaceFirstChar { it.uppercase() })
            DetailRow("Date", formatDate(encounter.encounterDate))
            encounter.department?.let { DetailRow("Department", it) }
            encounter.diagnosis?.let { DetailRow("Diagnosis", it) }
            encounter.notes?.let { DetailRow("Notes", it) }
            summary?.let { s ->
                Spacer(Modifier.height(8.dp))
                Text("Discharge Summary", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                HorizontalDivider()
                s.dischargingProviderName?.let { DetailRow("Provider", it) }
                s.dischargeDiagnosis?.let { DetailRow("Diagnosis", it) }
                s.dischargeCondition?.let { DetailRow("Condition", it) }
                s.disposition?.let { DetailRow("Disposition", it.replace("_", " ").replaceFirstChar { c -> c.uppercase() }) }
                s.followUpInstructions?.let { DetailRow("Follow-Up", it) }
                s.activityRestrictions?.let { DetailRow("Activity Restrictions", it) }
                s.dietInstructions?.let { DetailRow("Diet", it) }
                s.warningSigns?.let { DetailRow("Warning Signs", it) }
                s.dischargeDate?.let { DetailRow("Discharge Date", formatDate(it)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun SummaryCard(summary: DischargeSummaryDto) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header — always visible, tap to expand
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(summary.dischargingProviderName ?: "Provider",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    summary.hospitalName?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val dateStr = summary.dischargeDate ?: summary.dischargeTime
                    dateStr?.let {
                        Text(formatDate(it), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expandable body
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                summary.dischargeDiagnosis?.takeIf { it.isNotBlank() }?.let {
                    DetailRow("Diagnosis", it)
                }
                summary.dischargeCondition?.let { DetailRow("Condition", it) }
                summary.hospitalCourse?.let { DetailRow("Hospital Course", it) }
                summary.followUpInstructions?.let { DetailRow("Follow-up", it) }
                summary.activityRestrictions?.let { DetailRow("Activity Restrictions", it) }
                summary.dietInstructions?.let { DetailRow("Diet", it) }
                summary.warningSigns?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFEF3C7)) {
                        Text("⚠ $it", modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF92400E))
                    }
                }
                summary.medicationReconciliation?.takeIf { it.isNotEmpty() }?.let { meds ->
                    Spacer(Modifier.height(8.dp))
                    Text("Medications", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    meds.forEach { med ->
                        val parts = listOfNotNull(med.medicationName, med.dosage, med.frequency, med.reconciliationAction)
                        Text("• ${parts.joinToString(" — ")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                summary.followUpAppointments?.takeIf { it.isNotEmpty() }?.let { appts ->
                    Spacer(Modifier.height(8.dp))
                    Text("Follow-up Appointments", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    appts.forEach { appt ->
                        val parts = listOfNotNull(appt.providerName, appt.department, appt.appointmentDate)
                        Text("• ${parts.joinToString(" — ")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                summary.additionalNotes?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    DetailRow("Notes", it)
                }
            }
        }
    }
}
