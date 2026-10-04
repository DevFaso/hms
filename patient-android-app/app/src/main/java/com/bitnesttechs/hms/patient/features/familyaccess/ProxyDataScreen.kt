package com.bitnesttechs.hms.patient.features.familyaccess

import com.bitnesttechs.hms.patient.ui.theme.StatusNegativeOnLight
import com.bitnesttechs.hms.patient.core.network.FailureText
import com.bitnesttechs.hms.patient.core.network.AppText
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import com.bitnesttechs.hms.patient.ui.theme.OnBrandMuted
import com.bitnesttechs.hms.patient.ui.theme.BrandPrimary
import com.bitnesttechs.hms.patient.ui.theme.badgeFill
import com.bitnesttechs.hms.patient.ui.theme.onBadge
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
                    else -> error.value = AppText.get(R.string.proxy_data_unsupported)
                }
            } catch (e: Exception) {
                error.value = AppText.get(R.string.proxy_data_load_failed, FailureText.of(e))
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
        "VIEW_APPOINTMENTS" -> stringResource(R.string.appointments)
        "VIEW_MEDICATIONS" -> stringResource(R.string.medications)
        "VIEW_LAB_RESULTS" -> stringResource(R.string.lab_results)
        "VIEW_BILLING" -> stringResource(R.string.billing)
        "VIEW_RECORDS" -> stringResource(R.string.health_records)
        else -> stringResource(R.string.proxy_data_title)
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
                            color = OnBrandMuted
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandPrimary, titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandPrimary)
                }
            }
            error != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null, Modifier.size(48.dp), tint = ErrorRed)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: stringResource(R.string.error_generic), color = StatusNegativeOnLight)
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
                        Text(stringResource(R.string.proxy_data_unsupported))
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
        EmptyState(stringResource(R.string.proxy_no_appointments), Icons.Default.CalendarMonth, padding)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(appointments) { appt ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarToday, null, Modifier.size(20.dp), tint = BrandPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(appt.appointmentDate, fontWeight = FontWeight.Bold)
                        appt.timeDisplay?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    appt.staffName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(appt.statusEnum.labelRes), style = MaterialTheme.typography.labelSmall, color = statusColor(appt.status))
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
        EmptyState(stringResource(R.string.proxy_no_medications), Icons.Default.Medication, padding)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(medications) { med ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Medication, null, Modifier.size(24.dp), tint = BrandPrimary)
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
        EmptyState(stringResource(R.string.proxy_no_lab_results), Icons.Default.Science, padding)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(results) { lab ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Science, null, Modifier.size(24.dp), tint = BrandPrimary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lab.testName, fontWeight = FontWeight.Bold)
                        lab.displayDate?.let { Text(it.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (lab.isPending) {
                            Text(stringResource(R.string.lab_result_pending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            lab.valueWithUnit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    Surface(shape = RoundedCornerShape(12.dp), color = lab.tone.badgeFill().copy(alpha = 0.15f)) {
                        Text(stringResource(lab.statusLabelRes), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = lab.tone.onBadge())
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
        EmptyState(stringResource(R.string.proxy_no_billing), Icons.Default.Receipt, padding)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(invoices) { inv ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Receipt, null, Modifier.size(20.dp), tint = BrandPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(inv.invoiceNumber, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(inv.statusEnum.labelRes), style = MaterialTheme.typography.labelSmall, color = statusColor(inv.status))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text(stringResource(R.string.proxy_invoice_total, money(inv.totalAmount)), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.proxy_invoice_balance, money(inv.balanceDue)), style = MaterialTheme.typography.bodyMedium, color = if (inv.balanceDue > 0) ErrorRed else SuccessGreen)
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
        EmptyState(stringResource(R.string.proxy_no_records), Icons.Default.Visibility, padding)
        return
    }
    val s = summary!!
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Allergies
        if (!s.allergies.isNullOrEmpty()) {
            item {
                SectionCard(stringResource(R.string.allergies), Icons.Default.Warning) {
                    s.allergies!!.forEach { allergy ->
                        Text("• $allergy", style = MaterialTheme.typography.bodyMedium, color = ErrorRed)
                    }
                }
            }
        }
        // Chronic conditions
        if (!s.chronicConditions.isNullOrEmpty()) {
            item {
                SectionCard(stringResource(R.string.chronic_conditions), Icons.Default.MonitorHeart) {
                    s.chronicConditions!!.forEach { condition ->
                        Text("• $condition", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        // Active diagnoses
        if (!s.activeDiagnoses.isNullOrEmpty()) {
            item {
                SectionCard(stringResource(R.string.active_diagnoses), Icons.Default.MedicalInformation) {
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
                    Text(stringResource(R.string.proxy_summary), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.proxy_medication_count, s.medicationCount), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.proxy_lab_result_count, s.labResultCount), style = MaterialTheme.typography.bodyMedium)
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
                Icon(icon, null, Modifier.size(20.dp), tint = BrandPrimary)
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

/** The same amount format as the Billing screen (the proxy view printed "$"). */
private fun money(amount: Double): String = String.format(java.util.Locale.getDefault(), "%,.0f FCFA", amount)

private fun statusColor(status: String): Color {
    return when (status.uppercase()) {
        "COMPLETED", "PAID", "CONFIRMED" -> SuccessGreen
        "CANCELLED", "REJECTED", "OVERDUE" -> ErrorRed
        "PENDING", "SCHEDULED", "SENT" -> BrandPrimary
        else -> Color.Gray
    }
}
