package com.bitnesttechs.hms.patient.features.labresults

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
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.LabResultDto
import com.bitnesttechs.hms.patient.features.dashboard.StatusBadge
import com.bitnesttechs.hms.patient.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabResultsScreen(onBack: () -> Unit = {}, viewModel: LabResultsViewModel = hiltViewModel()) {
    val results by viewModel.results.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedResult by remember { mutableStateOf<LabResultDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lab Results") },
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
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (results.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Science, null, Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("No lab results", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            items(results) { lab ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { selectedResult = lab },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            lab.isCritical -> CriticalRed.copy(alpha = 0.05f)
                            lab.isAbnormal -> WarningAmber.copy(alpha = 0.05f)
                            else -> MaterialTheme.colorScheme.surface
                        }
                    ),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(lab.testName, style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                StatusBadge(
                                    text = lab.statusDisplay,
                                    color = when {
                                        lab.isCritical -> CriticalRed
                                        lab.isAbnormal -> WarningAmber
                                        else -> SuccessGreen
                                    }
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            lab.result?.let {
                                Row {
                                    Text("Result: ", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("$it ${lab.unit ?: ""}".trim(),
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                            }
                            lab.referenceRange?.let {
                                Text("Reference: $it", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            lab.resultDate?.let {
                                Text("Date: $it", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ChevronRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // Detail bottom sheet
    selectedResult?.let { lab ->
        LabResultDetailDialog(lab = lab, onDismiss = { selectedResult = null })
    }
}

@Composable
private fun LabResultDetailDialog(lab: LabResultDto, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(lab.testName, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Status: ", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusBadge(
                        text = lab.statusDisplay,
                        color = when {
                            lab.isCritical -> CriticalRed
                            lab.isAbnormal -> WarningAmber
                            else -> SuccessGreen
                        }
                    )
                }

                HorizontalDivider()

                // Result section
                Text("Results", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                lab.result?.let { DetailRow("Value", "$it ${lab.unit ?: ""}".trim()) }
                lab.referenceRange?.let { DetailRow("Reference Range", it) }

                HorizontalDivider()

                // Dates
                Text("Dates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                lab.collectionDate?.let { DetailRow("Collected", it.take(10)) }
                lab.resultDate?.let { DetailRow("Resulted", it.take(10)) }

                // Lab info
                lab.labName?.let {
                    HorizontalDivider()
                    Text("Lab", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    DetailRow("Lab Name", it)
                }

                lab.orderedBy?.let {
                    HorizontalDivider()
                    DetailRow("Ordered By", it)
                }

                lab.notes?.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    Text("Notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
