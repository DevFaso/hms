package com.bitnesttechs.hms.patient.features.visitsummaries

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.DischargeSummaryDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitSummariesScreen(
    onBack: () -> Unit = {},
    viewModel: VisitSummariesViewModel = hiltViewModel()
) {
    val summaries by viewModel.summaries.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visit Summaries") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandBlue,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = BrandBlue)
                }
            }
            error != null -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Unable to load visit summaries",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.load() }) {
                            Text("Retry")
                        }
                    }
                }
            }
            summaries.isEmpty() -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No visit summaries yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "After-visit summaries will appear here\nonce your visits are completed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(summaries) { summary ->
                        VisitSummaryCard(summary)
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun VisitSummaryCard(summary: DischargeSummaryDto) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header row: date + provider
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    summary.dischargingProviderName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    summary.hospitalName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    summary.encounterType?.let {
                        Text(
                            it.replace("_", " ").replaceFirstChar { c -> c.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                summary.dischargeDate?.let {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BrandLightBlue
                    ) {
                        Text(
                            it.take(10),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Diagnosis
            summary.dischargeDiagnosis?.let {
                SummaryField("Diagnosis", it)
            }

            // Treatment Summary / Hospital Course
            summary.hospitalCourse?.let {
                SummaryField("Treatment Summary", it)
            }

            // Discharge condition
            summary.dischargeCondition?.let {
                SummaryField("Condition at Discharge", it)
            }

            // Disposition
            summary.disposition?.let {
                SummaryField("Disposition", it.replace("_", " ").replaceFirstChar { c -> c.uppercase() })
            }

            // Medications
            summary.medicationReconciliation?.takeIf { it.isNotEmpty() }?.let { meds ->
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        "Medications",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    meds.forEach { med ->
                        Text(
                            listOfNotNull(med.medicationName, med.dosage, med.frequency)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Follow-up instructions
            summary.followUpInstructions?.let {
                SummaryField("Follow-Up Instructions", it)
            }

            // Activity restrictions
            summary.activityRestrictions?.let {
                SummaryField("Activity Restrictions", it)
            }

            // Diet instructions
            summary.dietInstructions?.let {
                SummaryField("Diet Instructions", it)
            }

            // Wound care instructions
            summary.woundCareInstructions?.let {
                SummaryField("Wound Care Instructions", it)
            }

            // Warning signs
            summary.warningSigns?.let {
                SummaryField("⚠️ Warning Signs", it)
            }

            // Patient education
            summary.patientEducationProvided?.let {
                SummaryField("Patient Education", it)
            }

            // Additional notes
            summary.additionalNotes?.let {
                SummaryField("Additional Notes", it)
            }
        }
    }
}

@Composable
private fun SummaryField(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
