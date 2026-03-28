package com.bitnesttechs.hms.patient.features.vitals

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.RecordVitalRequest
import com.bitnesttechs.hms.patient.core.models.VitalSignDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsScreen(onBack: () -> Unit = {}, viewModel: VitalsViewModel = hiltViewModel()) {
    val vitals by viewModel.vitals.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vitals") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue,
                    titleContentColor = Color.White)
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
            // Latest vitals grid
            vitals.firstOrNull()?.let { latest ->
                item {
                    Card(shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Latest Readings", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                latest.bloodPressureDisplay?.let { VitalTile("Blood Pressure", it, Icons.Default.Favorite, Modifier.weight(1f)) }
                                latest.heartRateDisplay?.let { VitalTile("Heart Rate", it, Icons.Default.MonitorHeart, Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                latest.temperatureDisplay?.let { VitalTile("Temperature", it, Icons.Default.Thermostat, Modifier.weight(1f)) }
                                latest.oxygenDisplay?.let { VitalTile("Oxygen Sat.", it, Icons.Default.Air, Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            // History
            if (vitals.size > 1) {
                item { Text("History", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                items(vitals.drop(1)) { vital ->
                    VitalHistoryRow(vital)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun VitalTile(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = BrandLightBlue) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = BrandBlue)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VitalHistoryRow(vital: VitalSignDto) {
    Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(vital.recordedAt.take(10), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                vital.bloodPressureDisplay?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                vital.heartRateDisplay?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                vital.oxygenDisplay?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordVitalSheet(
    isRecording: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (RecordVitalRequest) -> Unit
) {
    var systolic by remember { mutableStateOf("") }
    var diastolic by remember { mutableStateOf("") }
    var heartRate by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf("") }
    var oxygen by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Record New Vital", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(systolic, { systolic = it }, label = { Text("Systolic (mmHg)") },
                    modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(diastolic, { diastolic = it }, label = { Text("Diastolic") },
                    modifier = Modifier.weight(1f), singleLine = true)
            }
            OutlinedTextField(heartRate, { heartRate = it }, label = { Text("Heart Rate (bpm)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(temperature, { temperature = it }, label = { Text("Temperature (°C)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(oxygen, { oxygen = it }, label = { Text("Oxygen Saturation (%)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(), maxLines = 2)

            Button(
                onClick = {
                    onSubmit(RecordVitalRequest(
                        systolicBP = systolic.toIntOrNull(),
                        diastolicBP = diastolic.toIntOrNull(),
                        heartRate = heartRate.toIntOrNull(),
                        temperature = temperature.toDoubleOrNull(),
                        oxygenSaturation = oxygen.toIntOrNull(),
                        notes = notes.ifBlank { null }
                    ))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isRecording,
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) {
                if (isRecording) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Save Vitals")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
