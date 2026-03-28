package com.bitnesttechs.hms.patient.features.familyaccess

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.SuccessGreen
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProxyDataViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {

    val appointments = MutableStateFlow<List<AppointmentDto>>(emptyList())
    val medications = MutableStateFlow<List<MedicationDto>>(emptyList())
    val labResults = MutableStateFlow<List<LabResultDto>>(emptyList())
    val invoices = MutableStateFlow<List<InvoiceDto>>(emptyList())
    val healthSummary = MutableStateFlow<HealthSummaryDto?>(null)
    val isLoading = MutableStateFlow(true)
    val error = MutableStateFlow<String?>(null)

    fun load(patientId: String, permission: String) {
        viewModelScope.launch {
            isLoading.value = true
            error.value = null
            try {
                when (permission.uppercase()) {
                    "VIEW_APPOINTMENTS" -> {
                        val resp = api.getProxyAppointments(patientId)
                        appointments.value = resp.body()?.data ?: emptyList()
                    }
                    "VIEW_MEDICATIONS" -> {
                        val resp = api.getProxyMedications(patientId)
                        medications.value = resp.body()?.data ?: emptyList()
                    }
                    "VIEW_LAB_RESULTS" -> {
                        val resp = api.getProxyLabResults(patientId)
                        labResults.value = resp.body()?.data ?: emptyList()
                    }
                    "VIEW_BILLING" -> {
                        val resp = api.getProxyBilling(patientId)
                        invoices.value = resp.body()?.data?.content ?: emptyList()
                    }
                    "VIEW_RECORDS" -> {
                        val resp = api.getProxyRecords(patientId)
                        healthSummary.value = resp.body()?.data
                    }
                    else -> error.value = "Unsupported permission: $permission"
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Failed to load data"
            } finally {
                isLoading.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyDataScreen(
    patientId: String,
    permission: String,
    patientName: String,
    onBack: () -> Unit,
    viewModel: ProxyDataViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(patientId, permission) {
        viewModel.load(patientId, permission)
    }

    val title = when (permission.uppercase()) {
        "VIEW_APPOINTMENTS" -> "Appointments"
        "VIEW_MEDICATIONS" -> "Medications"
        "VIEW_LAB_RESULTS" -> "Lab Results"
        "VIEW_BILLING" -> "Billing"
        "VIEW_RECORDS" -> "Health Records"
        else -> "Data"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            patientName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandBlue, titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandBlue)
                }
            }
            error != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null, Modifier.size(48.dp), tint = ErrorRed)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "Error", color = ErrorRed)
                    }
                }
            }
            else -> {
                when (permission.uppercase()) {
                    "VIEW_APPOINTMENTS" -> AppointmentsList(viewModel, padding)
                    "VIEW_MEDICATIONS" -> MedicationsList(viewModel, padding)
                    "VIEW_LAB_RESULTS" -> LabResultsList(viewModel, padding)
                    "VIEW_BILLING" -> BillingList(viewModel, padding)
                    "VIEW_RECORDS" -> RecordsSummary(viewModel, padding)
                    else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text("Unsupported view")
                    }
                }
            }
        }
    }
}

@Composable
private fun AppointmentsList(viewModel: ProxyDataViewModel, padding: PaddingValues) {
    val appointments by viewModel.appointments.collectAsState()
    if (appointments.isEmpty()) {
        EmptyState("No appointments found", Icons.Default.CalendarMonth, padding)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(appointments) { appt ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarToday, null, Modifier.size(20.dp), tint = BrandBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(appt.appointmentDate, fontWeight = FontWeight.Bold)
                        appt.timeDisplay?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    appt.staffName?.let { Text("Dr. $it", style = MaterialTheme.typography.bodyMedium) }
                    Text(appt.statusDisplay, style = MaterialTheme.typography.labelSmall, color = statusColor(appt.status))
                    appt.reason?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicationsList(viewModel: ProxyDataViewModel, padding: PaddingValues) {
    val medications by viewModel.medications.collectAsState()
    if (medications.isEmpty()) {
        EmptyState("No medications found", Icons.Default.Medication, padding)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(medications) { med ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Medication, null, Modifier.size(24.dp), tint = BrandBlue)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(med.name, fontWeight = FontWeight.Bold)
                        med.dosage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        med.frequency?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabResultsList(viewModel: ProxyDataViewModel, padding: PaddingValues) {
    val results by viewModel.labResults.collectAsState()
    if (results.isEmpty()) {
        EmptyState("No lab results found", Icons.Default.Science, padding)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(results) { lab ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Science, null, Modifier.size(24.dp), tint = BrandBlue)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lab.testName, fontWeight = FontWeight.Bold)
                        lab.resultDate?.let { Text(it.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    lab.status?.let {
                        Surface(shape = RoundedCornerShape(12.dp), color = statusColor(it).copy(alpha = 0.15f)) {
                            Text(it, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = statusColor(it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BillingList(viewModel: ProxyDataViewModel, padding: PaddingValues) {
    val invoices by viewModel.invoices.collectAsState()
    if (invoices.isEmpty()) {
        EmptyState("No billing records found", Icons.Default.Receipt, padding)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(invoices) { inv ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Receipt, null, Modifier.size(20.dp), tint = BrandBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(inv.invoiceNumber, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(inv.status, style = MaterialTheme.typography.labelSmall, color = statusColor(inv.status))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text("Total: $${inv.totalAmount}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(16.dp))
                        Text("Balance: $${inv.balanceDue}", style = MaterialTheme.typography.bodyMedium, color = if (inv.balanceDue > 0) ErrorRed else SuccessGreen)
                    }
                    inv.invoiceDate?.let {
                        Text(it.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordsSummary(viewModel: ProxyDataViewModel, padding: PaddingValues) {
    val summary by viewModel.healthSummary.collectAsState()
    if (summary == null) {
        EmptyState("No records available", Icons.Default.Visibility, padding)
        return
    }
    val s = summary!!
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Allergies
        if (!s.allergies.isNullOrEmpty()) {
            item {
                SectionCard("Allergies", Icons.Default.Warning) {
                    s.allergies!!.forEach { allergy ->
                        Text("• $allergy", style = MaterialTheme.typography.bodyMedium, color = ErrorRed)
                    }
                }
            }
        }
        // Chronic conditions
        if (!s.chronicConditions.isNullOrEmpty()) {
            item {
                SectionCard("Chronic Conditions", Icons.Default.MonitorHeart) {
                    s.chronicConditions!!.forEach { condition ->
                        Text("• $condition", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        // Active diagnoses
        if (!s.activeDiagnoses.isNullOrEmpty()) {
            item {
                SectionCard("Active Diagnoses", Icons.Default.MedicalInformation) {
                    s.activeDiagnoses!!.forEach { dx ->
                        Text("• $dx", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        // Stats
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Summary", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Medications: ${s.medicationCount}", style = MaterialTheme.typography.bodyMedium)
                    Text("Lab Results: ${s.labResultCount}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = BrandBlue)
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun EmptyState(message: String, icon: androidx.compose.ui.graphics.vector.ImageVector, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun statusColor(status: String): Color {
    return when (status.uppercase()) {
        "COMPLETED", "PAID", "CONFIRMED" -> SuccessGreen
        "CANCELLED", "REJECTED", "OVERDUE" -> ErrorRed
        "PENDING", "SCHEDULED", "SENT" -> BrandBlue
        else -> Color.Gray
    }
}
