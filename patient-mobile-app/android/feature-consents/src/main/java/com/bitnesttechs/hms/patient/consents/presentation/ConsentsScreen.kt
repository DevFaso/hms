package com.bitnesttechs.hms.patient.consents.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.consents.data.ConsentDto
import com.bitnesttechs.hms.patient.core.designsystem.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentsScreen(viewModel: ConsentsViewModel = hiltViewModel()) {
    val consents by viewModel.consents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var consentToRevoke by remember { mutableStateOf<ConsentDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Sharing") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading && consents.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null && consents.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "Error", color = HmsError)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadConsents() }) { Text("Retry") }
                    }
                }
                consents.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Shield, null, tint = HmsTextTertiary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Consents", style = MaterialTheme.typography.titleMedium, color = HmsTextSecondary)
                    }
                }
                else -> {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(consents) { consent ->
                            ConsentCard(consent) {
                                consentToRevoke = consent
                            }
                        }
                    }
                }
            }
        }
    }

    consentToRevoke?.let { consent ->
        AlertDialog(
            onDismissRequest = { consentToRevoke = null },
            title = { Text("Revoke Consent") },
            text = { Text("Are you sure you want to revoke this data sharing consent?") },
            confirmButton = {
                TextButton(onClick = {
                    consent.fromHospitalId?.let { fromId ->
                        consent.toHospitalId?.let { toId ->
                            viewModel.revokeConsent(fromId, toId)
                        }
                    }
                    consentToRevoke = null
                }) { Text("Revoke", color = HmsError) }
            },
            dismissButton = {
                TextButton(onClick = { consentToRevoke = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ConsentCard(consent: ConsentDto, onRevoke: () -> Unit) {
    HmsCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(consent.fromHospitalName ?: "Hospital", style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ArrowForward, null, tint = HmsTextTertiary, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(consent.toHospitalName ?: "Hospital", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                    }
                }
                val statusColor = if (consent.isActive) HmsSuccess else HmsTextSecondary
                Surface(color = statusColor.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                    Text(
                        consent.status ?: "Unknown",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                consent.consentType?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
                Spacer(modifier = Modifier.weight(1f))
                consent.grantedDate?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
            }
            if (consent.isActive) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onRevoke, modifier = Modifier.align(Alignment.End)) {
                    Text("Revoke", color = HmsError)
                }
            }
        }
    }
}
