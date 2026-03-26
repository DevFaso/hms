package com.bitnesttechs.hms.patient.features.sharingprivacy

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.AccessLogDto
import com.bitnesttechs.hms.patient.core.models.ConsentDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingPrivacyScreen(viewModel: SharingPrivacyViewModel = hiltViewModel()) {
    val consents by viewModel.consents.collectAsState()
    val accessLog by viewModel.accessLog.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sharing & Privacy") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandBlue, titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("Consents", style = MaterialTheme.typography.labelSmall) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = { Text("Access Log", style = MaterialTheme.typography.labelSmall) })
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandBlue)
                }
                return@Column
            }

            when (selectedTab) {
                0 -> ConsentsTab(consents) { viewModel.revokeConsent(it) }
                1 -> AccessLogTab(accessLog)
            }
        }
    }
}

@Composable
private fun ConsentsTab(consents: List<ConsentDto>, onRevoke: (String) -> Unit) {
    if (consents.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No consent records", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(consents) { consent ->
            ConsentCard(consent, onRevoke)
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ConsentCard(consent: ConsentDto, onRevoke: (String) -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    val isActive = consent.status.uppercase() == "ACTIVE" || consent.status.uppercase() == "GRANTED"

    Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(consent.consentType.replace("_", " ").replaceFirstChar { it.uppercase() },
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(20.dp),
                    color = if (isActive) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)) {
                    Text(consent.status, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) Color(0xFF166534) else Color(0xFFDC2626),
                        fontWeight = FontWeight.SemiBold)
                }
            }
            consent.recipientName?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Person, null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            consent.expiresAt?.let {
                Text("Expires: ${it.take(10)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isActive) {
                TextButton(onClick = { showConfirm = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Revoke Access")
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Revoke Consent") },
            text = { Text("Are you sure you want to revoke this access?") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onRevoke(consent.id) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AccessLogTab(logs: List<AccessLogDto>) {
    if (logs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No access log entries", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(logs) { log ->
            Card(shape = RoundedCornerShape(10.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(log.action.replace("_", " ").replaceFirstChar { it.uppercase() },
                            fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                        log.accessedBy?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(log.accessedAt.take(10), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
