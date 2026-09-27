package com.bitnesttechs.hms.patient.features.visits

import com.bitnesttechs.hms.patient.core.models.DischargeDisposition
import com.bitnesttechs.hms.patient.core.models.EncounterStatus
import com.bitnesttechs.hms.patient.core.models.EncounterType
import com.bitnesttechs.hms.patient.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.DischargeSummaryDto
import com.bitnesttechs.hms.patient.core.models.EncounterDto
import com.bitnesttechs.hms.patient.core.models.FollowUpAppointmentDto
import com.bitnesttechs.hms.patient.core.models.MedicationReconciliationDto
import com.bitnesttechs.hms.patient.ui.theme.BrandPrimary
import com.bitnesttechs.hms.patient.ui.theme.BrandSoft

/** The date part, in the app language (it was an English month array). */
private fun formatDate(iso: String): String =
    runCatching {
        java.time.LocalDate.parse(iso.take(10)).format(
            java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(java.util.Locale.getDefault())
        )
    }.getOrDefault(iso.take(10))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitHistoryScreen(onBack: () -> Unit = {}, viewModel: VisitHistoryViewModel = hiltViewModel()) {
    val encounters by viewModel.encounters.collectAsState()
    val summaries by viewModel.summaries.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedEncounter by remember { mutableStateOf<EncounterDto?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.visits), stringResource(R.string.visit_summaries_tab))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.visit_history)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandPrimary, titleContentColor = Color.White
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
                    CircularProgressIndicator(color = BrandPrimary)
                }
                return@Column
            }

            when (selectedTab) {
                0 -> {
                    if (encounters.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_visits_on_record), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(stringResource(R.string.no_visit_summaries), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Surface(shape = RoundedCornerShape(10.dp), color = BrandSoft,
                modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.LocalHospital, null, tint = BrandPrimary, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Type chip like iOS
                    Surface(shape = RoundedCornerShape(4.dp), color = BrandPrimary.copy(alpha = 0.1f)) {
                        Text(stringResource(EncounterType.fromWire(encounter.encounterType).labelRes),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = BrandPrimary)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(encounter.doctorName ?: encounter.department ?: stringResource(R.string.provider_fallback),
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
        "IN_PROGRESS" -> Pair(Color(0xFFDBEAFE), BrandPrimary)
        "CANCELLED" -> Pair(Color(0xFFFEE2E2), Color(0xFFDC2626))
        else -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(20.dp), color = bg) {
        Text(stringResource(EncounterStatus.fromWire(status).labelRes), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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
            Text(stringResource(R.string.visit_details), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            DetailRow(stringResource(R.string.type_label), stringResource(EncounterType.fromWire(encounter.encounterType).labelRes))
            DetailRow(stringResource(R.string.date), formatDate(encounter.encounterDate))
            encounter.department?.let { DetailRow(stringResource(R.string.department), it) }
            encounter.diagnosis?.let { DetailRow(stringResource(R.string.visit_diagnosis), it) }
            encounter.notes?.let { DetailRow(stringResource(R.string.notes), it) }
            summary?.let { s ->
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.doc_type_discharge_summary), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                HorizontalDivider()
                s.dischargingProviderName?.let { DetailRow(stringResource(R.string.provider_fallback), it) }
                s.dischargeDiagnosis?.let { DetailRow(stringResource(R.string.visit_diagnosis), it) }
                s.dischargeCondition?.let { DetailRow(stringResource(R.string.visit_condition), it) }
                s.disposition?.let { DetailRow(stringResource(R.string.visit_disposition), stringResource(DischargeDisposition.fromWire(it).labelRes)) }
                s.followUpInstructions?.let { DetailRow(stringResource(R.string.treatment_plan_follow_up), it) }
                s.activityRestrictions?.let { DetailRow(stringResource(R.string.visit_activity_restrictions), it) }
                s.dietInstructions?.let { DetailRow(stringResource(R.string.visit_diet), it) }
                s.warningSigns?.let { DetailRow(stringResource(R.string.visit_warning_signs), it) }
                s.dischargeDate?.let { DetailRow(stringResource(R.string.visit_discharge_date), formatDate(it)) }
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
                    Text(summary.dischargingProviderName ?: stringResource(R.string.provider_fallback),
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
                    contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expandable body
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    summary.dischargeDiagnosis?.takeIf { it.isNotBlank() }?.let {
                        SummaryField(stringResource(R.string.visit_diagnosis), it)
                    }
                summary.dischargeCondition?.let { SummaryField(stringResource(R.string.visit_condition), it) }
                summary.hospitalCourse?.let { SummaryField(stringResource(R.string.visit_hospital_course), it) }
                summary.followUpInstructions?.let { SummaryField(stringResource(R.string.treatment_plan_follow_up), it) }
                summary.activityRestrictions?.let { SummaryField(stringResource(R.string.visit_activity_restrictions), it) }
                summary.dietInstructions?.let { SummaryField(stringResource(R.string.visit_diet), it) }
                summary.warningSigns?.takeIf { it.isNotBlank() }?.let {
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFEF3C7)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.visit_warning_signs), style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF92400E), fontWeight = FontWeight.SemiBold)
                            Text(it,
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF92400E))
                        }
                    }
                }
                summary.medicationReconciliation?.takeIf { it.isNotEmpty() }?.let { meds ->
                    SummaryMedicationList(meds)
                }
                summary.followUpAppointments?.takeIf { it.isNotEmpty() }?.let { appts ->
                    SummaryAppointmentList(appts)
                }
                summary.additionalNotes?.takeIf { it.isNotBlank() }?.let {
                    SummaryField(stringResource(R.string.notes), it)
                }
                }
            }
        }
    }
}

@Composable
private fun SummaryMedicationList(meds: List<MedicationReconciliationDto>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.medications), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        meds.forEach { med ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(med.medicationName ?: stringResource(R.string.medication), style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold)
                    listOfNotNull(med.dosage, med.frequency, med.reconciliationAction)
                        .takeIf { it.isNotEmpty() }
                        ?.let { details ->
                            Text(details.joinToString(" | "), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                }
            }
        }
    }
}

@Composable
private fun SummaryAppointmentList(appts: List<FollowUpAppointmentDto>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.visit_follow_up_appointments), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        appts.forEach { appt ->
            val details = listOfNotNull(appt.providerName, appt.department, appt.appointmentDate)
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()) {
                Text(details.joinToString(" | "), modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun SummaryField(label: String, value: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = BrandPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
