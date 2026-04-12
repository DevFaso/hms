package com.bitnesttechs.hms.patient.features.vitals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.bitnesttechs.hms.patient.core.models.VitalSignDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsScreen(onBack: () -> Unit = {}, viewModel: VitalsViewModel = hiltViewModel()) {
    val vitals by viewModel.vitals.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedVital by remember { mutableStateOf<VitalSignDto?>(null) }

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
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(2.dp),
                        modifier = Modifier.clickable { selectedVital = latest }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Latest Readings", style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold)
                                Icon(Icons.Default.ChevronRight, contentDescription = "View details",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                latest.bloodPressureDisplay?.let { VitalTile("Blood Pressure", it, Icons.Default.Favorite, color = Color(0xFFE53935), modifier = Modifier.weight(1f)) }
                                latest.heartRateDisplay?.let { VitalTile("Heart Rate", it, Icons.Default.MonitorHeart, color = Color(0xFFEC407A), modifier = Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                latest.temperatureDisplay?.let { VitalTile("Temperature", it, Icons.Default.Thermostat, color = Color(0xFFFB8C00), modifier = Modifier.weight(1f)) }
                                latest.oxygenDisplay?.let { VitalTile("SpO₂", it, Icons.Default.Air, color = Color(0xFF1E88E5), modifier = Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                latest.respiratoryRateDisplay?.let { VitalTile("Resp. Rate", it, Icons.Default.Air, color = Color(0xFF00897B), modifier = Modifier.weight(1f)) }
                                latest.bloodGlucoseDisplay?.let { VitalTile("Glucose", it, Icons.Default.Bloodtype, color = Color(0xFF5C6BC0), modifier = Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                latest.weightDisplay?.let { VitalTile("Weight", it, Icons.Default.FitnessCenter, color = Color(0xFF8E24AA), modifier = Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            // History
            if (vitals.size > 1) {
                item { Text("History", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                items(vitals.drop(1)) { vital ->
                    VitalHistoryRow(vital, onClick = { selectedVital = vital })
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // Detail dialog
    selectedVital?.let { vital ->
        VitalDetailDialog(vital = vital, onDismiss = { selectedVital = null })
    }
}

@Composable
private fun VitalTile(label: String, value: String, icon: ImageVector, color: Color = BrandBlue, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = color.copy(alpha = 0.08f)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1)
        }
    }
}

@Composable
private fun VitalHistoryRow(vital: VitalSignDto, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(vital.recordedDateDisplay, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ChevronRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            vital.sourceDisplay?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                vital.bloodPressureDisplay?.let { VitalMiniLabel("🩺", it) }
                vital.heartRateDisplay?.let { VitalMiniLabel("❤️", it) }
                vital.oxygenDisplay?.let { VitalMiniLabel("🫁", it) }
                vital.temperatureDisplay?.let { VitalMiniLabel("🌡️", it) }
            }
        }
    }
}

@Composable
private fun VitalMiniLabel(emoji: String, value: String) {
    Text("$emoji $value", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun VitalDetailDialog(vital: VitalSignDto, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Text("Vital Signs — ${vital.recordedDateDisplay}", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Recorded", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                VitalRow("Date/Time", vital.recordedDateDisplay)
                vital.sourceDisplay?.let { VitalRow("Source", it) }
                vital.recordedByName?.let { VitalRow("Recorded By", it) }
                HorizontalDivider()

                Text("Readings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                vital.bloodPressureDisplay?.let { VitalRow("Blood Pressure", it) }
                vital.heartRateDisplay?.let { VitalRow("Heart Rate", it) }
                vital.temperatureDisplay?.let { VitalRow("Temperature", it) }
                vital.oxygenDisplay?.let { VitalRow("Oxygen Saturation", it) }
                vital.respiratoryRateDisplay?.let { VitalRow("Respiratory Rate", it) }
                vital.bloodGlucoseDisplay?.let { VitalRow("Blood Glucose", it) }
                vital.weightDisplay?.let { VitalRow("Weight", it) }
                vital.bodyPosition?.let { VitalRow("Body Position", it.replace("_", " ").replaceFirstChar { c -> c.uppercase() }) }

                if (vital.clinicallySignificant == true) {
                    HorizontalDivider()
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFEF3C7)) {
                        Text("⚠ Clinically Significant", modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF92400E),
                            fontWeight = FontWeight.SemiBold)
                    }
                }

                vital.notes?.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    Text("Notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

@Composable
private fun VitalRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}


