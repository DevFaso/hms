package com.bitnesttechs.hms.patient.accesslogs.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.accesslogs.data.AccessLogEntryDto
import com.bitnesttechs.hms.patient.core.designsystem.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessLogsScreen(viewModel: AccessLogsViewModel = hiltViewModel()) {
    val logs by viewModel.logs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Access Log") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading && logs.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null && logs.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "Error", color = HmsError)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadLogs(reset = true) }) { Text("Retry") }
                    }
                }
                logs.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Visibility, null, tint = HmsTextTertiary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Access Logs", style = MaterialTheme.typography.titleMedium, color = HmsTextSecondary)
                    }
                }
                else -> {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(logs) { entry -> AccessLogCard(entry) }
                        item {
                            LaunchedEffect(Unit) { viewModel.loadLogs() }
                            if (isLoading) {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessLogCard(entry: AccessLogEntryDto) {
    val icon = accessIcon(entry.accessType)

    HmsCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = HmsPrimary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(entry.accessedBy ?: "Unknown User", style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary, modifier = Modifier.weight(1f))
                entry.accessDate?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
            }
            entry.accessedByRole?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = HmsAccent)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.accessType?.let {
                    Surface(color = HmsInfo.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                        Text(it, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = HmsInfo)
                    }
                }
                entry.resourceType?.let {
                    Surface(color = HmsTextSecondary.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                        Text(it, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = HmsTextSecondary)
                    }
                }
            }
            entry.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary, maxLines = 2)
            }
        }
    }
}

private fun accessIcon(type: String?): ImageVector = when (type?.uppercase()) {
    "VIEW", "READ" -> Icons.Default.Visibility
    "UPDATE", "EDIT" -> Icons.Default.Edit
    "CREATE" -> Icons.Default.AddCircle
    "DELETE" -> Icons.Default.Delete
    "PRINT" -> Icons.Default.Print
    "EXPORT" -> Icons.Default.FileUpload
    else -> Icons.Default.Search
}
