package com.bitnesttechs.hms.patient.labresults.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import com.bitnesttechs.hms.patient.labresults.data.LabResultDto
import com.bitnesttechs.hms.patient.labresults.data.LabTestResultDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabResultsScreen(viewModel: LabResultsViewModel = hiltViewModel()) {
    val results by viewModel.results.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var selectedResult by remember { mutableStateOf<LabResultDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lab Results") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading && results.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                error != null && results.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(error ?: "Error", color = HmsError)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadResults() }) { Text("Retry") }
                    }
                }
                results.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Science, null, tint = HmsTextTertiary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Lab Results", style = MaterialTheme.typography.titleMedium, color = HmsTextSecondary)
                    }
                }
                else -> {
                    if (selectedResult != null) {
                        LabResultDetail(result = selectedResult!!, onBack = { selectedResult = null })
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(results) { result ->
                                LabResultCard(result) { selectedResult = result }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabResultCard(result: LabResultDto, onClick: () -> Unit) {
    HmsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result.testName ?: "Lab Test",
                    style = MaterialTheme.typography.titleSmall,
                    color = HmsTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(result.status ?: "Unknown")
            }
            Spacer(modifier = Modifier.height(4.dp))
            result.orderedBy?.let {
                Text("Dr. $it", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
            }
            (result.resultDate ?: result.orderedDate)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val color = when (status.uppercase()) {
        "COMPLETED" -> HmsSuccess
        "PENDING" -> HmsWarning
        "CANCELLED" -> HmsError
        else -> HmsTextSecondary
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            status,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun LabResultDetail(result: LabResultDto, onBack: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("← Back to Results") }
        }
        item {
            HmsCard {
                Column {
                    Text(result.testName ?: "Lab Test", style = MaterialTheme.typography.headlineSmall, color = HmsTextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    result.labName?.let { Text("Lab: $it", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary) }
                    result.orderedBy?.let { Text("Dr. $it", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary) }
                    Row {
                        result.orderedDate?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
                        Spacer(modifier = Modifier.weight(1f))
                        StatusBadge(result.status ?: "Unknown")
                    }
                }
            }
        }
        result.results?.let { tests ->
            if (tests.isNotEmpty()) {
                item { HmsSectionHeader(title = "Results") }
                items(tests) { test -> TestResultRow(test) }
            }
        }
        result.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            item {
                HmsSectionHeader(title = "Notes")
                HmsCard { Text(notes, style = MaterialTheme.typography.bodyMedium, color = HmsTextPrimary) }
            }
        }
    }
}

@Composable
private fun TestResultRow(test: LabTestResultDto) {
    HmsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    test.parameterName ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (test.isAbnormal == true) HmsError else HmsTextPrimary
                )
                test.referenceRange?.let { Text("Ref: $it", style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    test.value ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (test.isAbnormal == true) HmsError else HmsTextPrimary
                )
                test.unit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
            }
            if (test.isAbnormal == true) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Default.Warning, null, tint = HmsError, modifier = Modifier.size(16.dp))
            }
        }
    }
}
