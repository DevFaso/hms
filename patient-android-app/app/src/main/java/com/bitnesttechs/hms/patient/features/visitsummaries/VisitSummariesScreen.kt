package com.bitnesttechs.hms.patient.features.visitsummaries

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.DischargeSummaryDto
import com.bitnesttechs.hms.patient.core.models.MedicationReconciliationDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue
import com.bitnesttechs.hms.patient.ui.theme.WarningOrange
import com.bitnesttechs.hms.patient.ui.theme.SuccessGreen

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
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            // ── Header ──
            CardHeader(summary)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── Body ──
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Encounter type badge
                summary.encounterType?.let { type ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = BrandBlue.copy(alpha = 0.1f)
                    ) {
                        Text(
                            type.replace("_", " "),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = BrandBlue
                        )
                    }
                }

                // Diagnosis
                summary.dischargeDiagnosis?.let {
                    IconSection(icon = "🩺", title = "DIAGNOSIS", body = it, accentColor = Color(0xFFE53935))
                }

                // Treatment Summary
                summary.hospitalCourse?.let {
                    IconSection(icon = "📋", title = "TREATMENT SUMMARY", body = it, accentColor = Color(0xFF7B1FA2))
                }

                // Disposition + Condition pills
                if (summary.disposition != null || summary.dischargeCondition != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        summary.disposition?.let { disposition ->
                            InfoPill(
                                label = "Disposition",
                                value = disposition.replace("_", " ")
                                    .replaceFirstChar { c -> c.uppercase() },
                                color = Color(0xFF3949AB),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        summary.dischargeCondition?.let { condition ->
                            InfoPill(
                                label = "Condition",
                                value = condition.replaceFirstChar { c -> c.uppercase() },
                                color = Color(0xFF00897B),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Medications
                summary.medicationReconciliation?.takeIf { it.isNotEmpty() }?.let { meds ->
                    MedicationsSection(meds)
                }

                // Follow-up instructions
                summary.followUpInstructions?.let {
                    IconSection(icon = "📅", title = "FOLLOW-UP INSTRUCTIONS", body = it, accentColor = WarningOrange)
                }

                // Activity restrictions
                summary.activityRestrictions?.let {
                    IconSection(icon = "🚶", title = "ACTIVITY RESTRICTIONS", body = it, accentColor = Color(0xFF0097A7))
                }

                // Diet instructions
                summary.dietInstructions?.let {
                    IconSection(icon = "🍎", title = "DIET INSTRUCTIONS", body = it, accentColor = Color(0xFF43A047))
                }

                // Wound care
                summary.woundCareInstructions?.let {
                    IconSection(icon = "🩹", title = "WOUND CARE", body = it, accentColor = Color(0xFFE91E63))
                }

                // Warning signs
                summary.warningSigns?.let { warnings ->
                    WarningSection(warnings)
                }

                // Patient education
                summary.patientEducationProvided?.let {
                    IconSection(icon = "📖", title = "PATIENT EDUCATION", body = it, accentColor = BrandBlue)
                }

                // Additional notes
                summary.additionalNotes?.let {
                    IconSection(icon = "📝", title = "ADDITIONAL NOTES", body = it, accentColor = Color(0xFF757575))
                }
            }
        }
    }
}

@Composable
private fun CardHeader(summary: DischargeSummaryDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Provider avatar
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(BrandBlue.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = (summary.dischargingProviderName?.firstOrNull()?.uppercase() ?: "P"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BrandBlue
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            summary.dischargingProviderName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            summary.hospitalName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            summary.dischargeDate?.let {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BrandLightBlue
                ) {
                    Text(
                        formatDateShort(it.take(10)),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlue,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val isFinalized = summary.isFinalized == true
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isFinalized) SuccessGreen.copy(alpha = 0.12f) else WarningOrange.copy(alpha = 0.12f)
            ) {
                Text(
                    if (isFinalized) "FINALIZED" else "DRAFT",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = if (isFinalized) SuccessGreen else WarningOrange
                )
            }
        }
    }
}

@Composable
private fun IconSection(
    icon: String,
    title: String,
    body: String,
    accentColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = icon,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 1.dp)
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun InfoPill(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.07f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = color.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = color
            )
        }
    }
}

@Composable
private fun MedicationsSection(meds: List<MedicationReconciliationDto>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💊", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "MEDICATIONS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = BrandBlue.copy(alpha = 0.12f)
            ) {
                Text(
                    "${meds.size}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    ),
                    color = BrandBlue
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            Column {
                meds.forEachIndexed { index, med ->
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(BrandBlue.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💊", fontSize = 14.sp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                med.medicationName ?: "Unknown",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                            )
                            val details = listOfNotNull(med.dosage, med.frequency)
                                .joinToString("  •  ")
                            if (details.isNotEmpty()) {
                                Text(
                                    details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (index < meds.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 54.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningSection(warnings: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WarningOrange.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, WarningOrange.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(WarningOrange),
                contentAlignment = Alignment.Center
            ) {
                Text("⚠️", fontSize = 14.sp)
            }
            Column {
                Text(
                    "WARNING SIGNS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        fontSize = 10.sp
                    ),
                    color = WarningOrange
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    warnings,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun formatDateShort(iso: String): String {
    return try {
        val parts = iso.split("-")
        if (parts.size == 3) {
            val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val month = months[parts[1].toInt() - 1]
            "$month ${parts[2].toInt()}, ${parts[0]}"
        } else iso
    } catch (_: Exception) { iso }
}
