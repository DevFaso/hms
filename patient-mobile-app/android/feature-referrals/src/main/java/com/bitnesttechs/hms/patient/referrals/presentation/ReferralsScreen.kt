package com.bitnesttechs.hms.patient.referrals.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import com.bitnesttechs.hms.patient.referrals.data.ReferralDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferralsScreen(viewModel: ReferralsViewModel = hiltViewModel()) {
    val referrals by viewModel.referrals.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var selectedReferral by remember { mutableStateOf<ReferralDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Referrals") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        when {
            isLoading && referrals.isEmpty() -> HmsLoadingView("Loading referrals...", modifier = Modifier.padding(padding))
            error != null && referrals.isEmpty() -> HmsErrorView(
                message = error ?: "Failed to load referrals",
                onRetry = { viewModel.loadReferrals() },
                modifier = Modifier.padding(padding)
            )
            referrals.isEmpty() -> HmsEmptyState(
                icon = Icons.Default.CallSplit,
                title = "No Referrals",
                message = "You have no referrals.",
                modifier = Modifier.padding(padding)
            )
            selectedReferral != null -> {
                ReferralDetailContent(
                    referral = selectedReferral!!,
                    onBack = { selectedReferral = null },
                    modifier = Modifier.padding(padding)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(referrals, key = { it.id }) { referral ->
                        ReferralCard(referral) { selectedReferral = referral }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferralCard(referral: ReferralDto, onClick: () -> Unit) {
    val urgencyColor = when (referral.urgency?.lowercase()) {
        "urgent", "emergency" -> HmsError
        "high" -> HmsWarning
        "routine", "normal" -> HmsSuccess
        else -> HmsTextTertiary
    }

    HmsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CallSplit, null, Modifier.size(18.dp), tint = urgencyColor)
                Spacer(Modifier.width(8.dp))
                Text(
                    referral.referralReason ?: referral.referralType ?: "Referral",
                    style = MaterialTheme.typography.titleSmall,
                    color = HmsTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(referral.status ?: "unknown")
            }
            referral.referredToDoctorName?.let {
                Row {
                    Icon(Icons.Default.Person, null, Modifier.size(14.dp), tint = HmsTextTertiary)
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                }
            }
            referral.referredToDepartment?.let {
                Row {
                    Icon(Icons.Default.Business, null, Modifier.size(14.dp), tint = HmsTextTertiary)
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                }
            }
            Row {
                referral.referralDate?.let {
                    Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary)
                    Spacer(Modifier.weight(1f))
                }
                referral.referralNumber?.let {
                    Text("#$it", style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferralDetailContent(referral: ReferralDto, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier) {
        TopAppBar(
            title = { Text("Referral Details") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
        )
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                HmsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(referral.referralReason ?: "Referral", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusBadge(referral.status ?: "unknown")
                        }
                        referral.referralNumber?.let { Text("Ref #$it", style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
                        referral.urgency?.let { Text("Urgency: ${it.replaceFirstChar { c -> c.uppercase() }}", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary) }
                        referral.referralDate?.let { Text("Date: ${it.take(10)}", style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
                    }
                }
            }
            item {
                HmsSectionHeader(title = "Referred By")
                HmsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        referral.referringDoctorName?.let { InfoRow(Icons.Default.Person, it) }
                        referral.referringDepartment?.let { InfoRow(Icons.Default.Business, it) }
                        referral.referringHospital?.let { InfoRow(Icons.Default.LocalHospital, it) }
                    }
                }
            }
            item {
                HmsSectionHeader(title = "Referred To")
                HmsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        referral.referredToDoctorName?.let { InfoRow(Icons.Default.Person, it) }
                        referral.referredToDepartment?.let { InfoRow(Icons.Default.Business, it) }
                        referral.referredToHospital?.let { InfoRow(Icons.Default.LocalHospital, it) }
                    }
                }
            }
            referral.diagnosisDescription?.let { diag ->
                item {
                    HmsSectionHeader(title = "Diagnosis")
                    HmsCard {
                        Column {
                            Text(diag, style = MaterialTheme.typography.bodyMedium)
                            referral.diagnosisCode?.let { Text("Code: $it", style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary) }
                        }
                    }
                }
            }
            referral.clinicalNotes?.let { notes ->
                item {
                    HmsSectionHeader(title = "Clinical Notes")
                    HmsCard { Text(notes, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            referral.appointmentDate?.let { date ->
                item {
                    HmsSectionHeader(title = "Appointment")
                    HmsCard { InfoRow(Icons.Default.CalendarToday, date.take(16)) }
                }
            }
            referral.completedDate?.let { date ->
                item {
                    HmsSectionHeader(title = "Completion")
                    HmsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = HmsSuccess)
                                Spacer(Modifier.width(4.dp))
                                Text("Completed: ${date.take(10)}", style = MaterialTheme.typography.bodySmall, color = HmsSuccess)
                            }
                            referral.completionNotes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = HmsTextTertiary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = HmsTextPrimary)
    }
}

@Composable
private fun StatusBadge(status: String) {
    val color = when (status.lowercase()) {
        "completed" -> HmsSuccess
        "pending", "requested" -> HmsWarning
        "cancelled", "rejected" -> HmsError
        "accepted", "active" -> HmsInfo
        else -> HmsTextTertiary
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(
            status.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
