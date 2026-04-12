package com.bitnesttechs.hms.patient.features.visits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
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
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedEncounter by remember { mutableStateOf<EncounterDto?>(null) }

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
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        if (encounters.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No visits on record", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(encounters) { encounter ->
                EncounterRow(encounter) { selectedEncounter = encounter }
            }
            item { Spacer(Modifier.height(16.dp)) }
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
                Text(encounter.encounterType.replace("_", " ").replaceFirstChar { it.uppercase() },
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(encounter.department ?: "General",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
