package com.bitnesttechs.hms.patient.immunizations.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import com.bitnesttechs.hms.patient.immunizations.data.ImmunizationDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmunizationsScreen(viewModel: ImmunizationsViewModel = hiltViewModel()) {
    val immunizations by viewModel.immunizations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var selectedImm by remember { mutableStateOf<ImmunizationDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Immunizations") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading && immunizations.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null && immunizations.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "Error", color = HmsError)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadImmunizations() }) { Text("Retry") }
                    }
                }
                immunizations.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Vaccines, null, tint = HmsTextTertiary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Immunizations", style = MaterialTheme.typography.titleMedium, color = HmsTextSecondary)
                    }
                }
                else -> {
                    if (selectedImm != null) {
                        ImmunizationDetail(selectedImm!!) { selectedImm = null }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(immunizations) { imm ->
                                ImmunizationCard(imm) { selectedImm = imm }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImmunizationCard(imm: ImmunizationDto, onClick: () -> Unit) {
    HmsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Vaccines, null, tint = HmsAccent, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(imm.vaccineName ?: "Vaccine", style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (imm.doseNumber != null && imm.totalDoses != null) {
                        Text("Dose ${imm.doseNumber}/${imm.totalDoses}", style = MaterialTheme.typography.bodySmall, color = HmsAccent)
                    }
                    imm.administeredDate?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
                }
            }
            val statusColor = if (imm.status?.uppercase() == "COMPLETED") HmsSuccess else HmsInfo
            Surface(color = statusColor.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                Text(
                    imm.status ?: "Given",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
        }
    }
}

@Composable
private fun ImmunizationDetail(imm: ImmunizationDto, onBack: () -> Unit) {
    val details = listOfNotNull(
        imm.vaccineCode?.let { "Vaccine Code" to it },
        (imm.doseNumber != null && imm.totalDoses != null).let {
            if (it) "Dose" to "${imm.doseNumber} of ${imm.totalDoses}" else null
        },
        imm.administeredDate?.let { "Administered" to it },
        imm.administeredBy?.let { "Administered By" to it },
        imm.site?.let { "Site" to it },
        imm.lotNumber?.let { "Lot Number" to it },
        imm.manufacturer?.let { "Manufacturer" to it },
        imm.expirationDate?.let { "Expiration" to it },
    )

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("← Back") } }
        item {
            HmsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Vaccines, null, tint = HmsAccent, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(imm.vaccineName ?: "Vaccine", style = MaterialTheme.typography.headlineSmall, color = HmsTextPrimary)
                }
            }
        }
        items(details) { (label, value) ->
            HmsCard {
                Column {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
                    Text(value, style = MaterialTheme.typography.bodyLarge, color = HmsTextPrimary)
                }
            }
        }
        imm.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            item {
                HmsSectionHeader(title = "Notes")
                HmsCard { Text(notes, style = MaterialTheme.typography.bodyMedium, color = HmsTextPrimary) }
            }
        }
    }
}
