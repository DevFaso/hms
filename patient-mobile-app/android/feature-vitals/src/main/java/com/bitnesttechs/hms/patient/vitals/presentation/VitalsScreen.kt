package com.bitnesttechs.hms.patient.vitals.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import com.bitnesttechs.hms.patient.vitals.data.VitalDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsScreen(viewModel: VitalsViewModel = hiltViewModel()) {
    val vitals by viewModel.vitals.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var showRecordSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vitals") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface),
                actions = {
                    IconButton(onClick = { showRecordSheet = true }) {
                        Icon(Icons.Default.Add, "Record Vital")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading && vitals.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null && vitals.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "Error", color = HmsError)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadVitals() }) { Text("Retry") }
                    }
                }
                vitals.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MonitorHeart, null, tint = HmsTextTertiary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Vitals", style = MaterialTheme.typography.titleMedium, color = HmsTextSecondary)
                        Text("Tap + to record a reading", style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
                    }
                }
                else -> {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(vitals) { vital -> VitalRow(vital) }
                    }
                }
            }
        }

        if (showRecordSheet) {
            RecordVitalDialog(
                onDismiss = { showRecordSheet = false },
                onSave = { type, value, unit, notes ->
                    viewModel.recordVital(type, value, unit, notes)
                    showRecordSheet = false
                }
            )
        }
    }
}

@Composable
private fun VitalRow(vital: VitalDto) {
    val icon = when (vital.type?.lowercase()) {
        "blood_pressure", "bloodpressure" -> Icons.Default.Favorite
        "heart_rate", "heartrate", "pulse" -> Icons.Default.MonitorHeart
        "temperature" -> Icons.Default.Thermostat
        "weight" -> Icons.Default.FitnessCenter
        "oxygen_saturation", "spo2" -> Icons.Default.Air
        else -> Icons.Default.MonitorHeart
    }

    HmsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = HmsPrimary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    vital.type?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "Vital",
                    style = MaterialTheme.typography.titleSmall,
                    color = HmsTextPrimary
                )
                vital.recordedAt?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
            }
            Text(vital.displayValue, style = MaterialTheme.typography.titleMedium, color = HmsPrimary)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RecordVitalDialog(onDismiss: () -> Unit, onSave: (String, Double, String, String?) -> Unit) {
    val types = listOf(
        Triple("blood_pressure", "Blood Pressure", "mmHg"),
        Triple("heart_rate", "Heart Rate", "bpm"),
        Triple("temperature", "Temperature", "°F"),
        Triple("weight", "Weight", "kg"),
        Triple("oxygen_saturation", "Oxygen Saturation", "%"),
        Triple("blood_glucose", "Blood Glucose", "mg/dL"),
        Triple("respiratory_rate", "Respiratory Rate", "breaths/min"),
    )

    var selectedIndex by remember { mutableIntStateOf(0) }
    var value by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record Vital") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = types[selectedIndex].second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        types.forEachIndexed { index, type ->
                            DropdownMenuItem(
                                text = { Text(type.second) },
                                onClick = { selectedIndex = index; expanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value (${types[selectedIndex].third})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    value.toDoubleOrNull()?.let { v ->
                        onSave(types[selectedIndex].first, v, types[selectedIndex].third, notes.ifBlank { null })
                    }
                },
                enabled = value.toDoubleOrNull() != null
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
