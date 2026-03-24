package com.bitnesttechs.hms.patient.consultations.presentation

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.consultations.data.ConsultationDto
import com.bitnesttechs.hms.patient.core.designsystem.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsultationsScreen(viewModel: ConsultationsViewModel = hiltViewModel()) {
    val consultations by viewModel.consultations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var selectedConsultation by remember { mutableStateOf<ConsultationDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Consultations") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        when {
            isLoading && consultations.isEmpty() -> HmsLoadingView("Loading consultations...", modifier = Modifier.padding(padding))
            error != null && consultations.isEmpty() -> HmsErrorView(
                message = error ?: "Failed to load consultations",
                onRetry = { viewModel.loadConsultations() },
                modifier = Modifier.padding(padding)
            )
            consultations.isEmpty() -> HmsEmptyState(
                icon = Icons.Default.Groups,
                title = "No Consultations",
                message = "You have no consultations.",
                modifier = Modifier.padding(padding)
            )
            selectedConsultation != null -> {
                ConsultationDetailContent(
                    consultation = selectedConsultation!!,
                    onBack = { selectedConsultation = null },
                    modifier = Modifier.padding(padding)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(consultations, key = { it.id }) { c ->
                        ConsultationCard(c) { selectedConsultation = c }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsultationCard(c: ConsultationDto, onClick: () -> Unit) {
    val priorityColor = when (c.priority?.lowercase()) {
        "urgent", "emergency", "high" -> HmsError
        "medium", "normal" -> HmsWarning
        "low", "routine" -> HmsSuccess
        else -> HmsTextTertiary
    }

    HmsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.reason ?: c.consultationType ?: "Consultation",
                    style = MaterialTheme.typography.titleSmall,
                    color = HmsTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(c.status ?: "unknown")
            }
            c.consultantDoctorName?.let {
                Row {
                    Icon(Icons.Default.Person, null, Modifier.size(14.dp), tint = HmsTextTertiary)
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                }
            }
            (c.consultantSpecialty ?: c.consultantDepartment)?.let {
                Row {
                    Icon(Icons.Default.Business, null, Modifier.size(14.dp), tint = HmsTextTertiary)
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                }
            }
            Row {
                (c.scheduledDate ?: c.requestedDate)?.let {
                    Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary)
                }
                Spacer(Modifier.weight(1f))
                c.priority?.let {
                    Surface(color = priorityColor.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                        Text(it.replaceFirstChar { ch -> ch.uppercase() }, style = MaterialTheme.typography.labelSmall, color = priorityColor, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsultationDetailContent(consultation: ConsultationDto, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier) {
        TopAppBar(
            title = { Text("Consultation Details") },
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
                            Text(consultation.reason ?: "Consultation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusChip(consultation.status ?: "unknown")
                        }
                        consultation.consultationType?.let { Text("Type: $it", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary) }
                        consultation.priority?.let { Text("Priority: ${it.replaceFirstChar { c -> c.uppercase() }}", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary) }
                    }
                }
            }
            if (consultation.requestingDoctorName != null || consultation.requestingDepartment != null) {
                item {
                    HmsSectionHeader(title = "Requested By")
                    HmsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            consultation.requestingDoctorName?.let { Row { Icon(Icons.Default.Person, null, Modifier.size(16.dp), tint = HmsTextTertiary); Spacer(Modifier.width(8.dp)); Text(it) } }
                            consultation.requestingDepartment?.let { Row { Icon(Icons.Default.Business, null, Modifier.size(16.dp), tint = HmsTextTertiary); Spacer(Modifier.width(8.dp)); Text(it) } }
                        }
                    }
                }
            }
            if (consultation.consultantDoctorName != null) {
                item {
                    HmsSectionHeader(title = "Consultant")
                    HmsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            consultation.consultantDoctorName?.let { Row { Icon(Icons.Default.Person, null, Modifier.size(16.dp), tint = HmsTextTertiary); Spacer(Modifier.width(8.dp)); Text(it) } }
                            consultation.consultantSpecialty?.let { Row { Icon(Icons.Default.MedicalServices, null, Modifier.size(16.dp), tint = HmsTextTertiary); Spacer(Modifier.width(8.dp)); Text(it) } }
                            consultation.consultantDepartment?.let { Row { Icon(Icons.Default.Business, null, Modifier.size(16.dp), tint = HmsTextTertiary); Spacer(Modifier.width(8.dp)); Text(it) } }
                        }
                    }
                }
            }
            item {
                HmsSectionHeader(title = "Timeline")
                HmsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        consultation.requestedDate?.let { Row { Icon(Icons.Default.CalendarToday, null, Modifier.size(16.dp), tint = HmsTextTertiary); Spacer(Modifier.width(8.dp)); Text("Requested: ${it.take(10)}") } }
                        consultation.scheduledDate?.let { Row { Icon(Icons.Default.Event, null, Modifier.size(16.dp), tint = HmsTextTertiary); Spacer(Modifier.width(8.dp)); Text("Scheduled: ${it.take(16)}") } }
                        consultation.completedDate?.let { Row { Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = HmsSuccess); Spacer(Modifier.width(8.dp)); Text("Completed: ${it.take(10)}", color = HmsSuccess) } }
                    }
                }
            }
            consultation.findings?.let { item { HmsSectionHeader(title = "Findings"); HmsCard { Text(it) } } }
            consultation.diagnosis?.let { item { HmsSectionHeader(title = "Diagnosis"); HmsCard { Text(it) } } }
            consultation.recommendations?.let { item { HmsSectionHeader(title = "Recommendations"); HmsCard { Text(it) } } }
            consultation.notes?.let { item { HmsSectionHeader(title = "Notes"); HmsCard { Text(it) } } }
            if (consultation.followUpRequired == true) {
                item {
                    HmsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Icon(Icons.Default.Replay, null, Modifier.size(16.dp), tint = HmsWarning)
                                Spacer(Modifier.width(8.dp))
                                Text("Follow-up Required", style = MaterialTheme.typography.titleSmall, color = HmsWarning)
                            }
                            consultation.followUpDate?.let { Text("Date: ${it.take(10)}", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = when (status.lowercase()) {
        "completed" -> HmsSuccess
        "pending", "requested" -> HmsWarning
        "cancelled" -> HmsError
        "scheduled", "active" -> HmsInfo
        else -> HmsTextTertiary
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(status.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
