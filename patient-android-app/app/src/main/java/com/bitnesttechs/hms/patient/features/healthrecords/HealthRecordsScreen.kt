package com.bitnesttechs.hms.patient.features.healthrecords

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.CurrentMedicationDto
import com.bitnesttechs.hms.patient.core.models.HealthSummaryDto
import com.bitnesttechs.hms.patient.core.models.ImmunizationDto
import com.bitnesttechs.hms.patient.core.models.LabResultDto
import com.bitnesttechs.hms.patient.core.models.ReferralDto
import com.bitnesttechs.hms.patient.core.models.TreatmentPlanDto
import com.bitnesttechs.hms.patient.core.models.VitalSignDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthRecordsScreen(onBack: () -> Unit = {}, viewModel: HealthRecordsViewModel = hiltViewModel()) {
    val summary by viewModel.summary.collectAsState()
    val immunizations by viewModel.immunizations.collectAsState()
    val treatmentPlans by viewModel.treatmentPlans.collectAsState()
    val referrals by viewModel.referrals.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Vitals", "Labs", "Medications", "Immunizations", "Treatment", "Referrals")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health Records") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue, titleContentColor = Color.White)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PatientIdentityHeader(summary)
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandBlue)
                }
                return@Column
            }

            when (selectedTab) {
                0 -> OverviewTab(summary)
                1 -> VitalsTab(summary?.recentVitals ?: emptyList())
                2 -> LabsTab(summary?.recentLabResults ?: emptyList())
                3 -> MedicationsTab(summary?.currentMedications ?: emptyList())
                4 -> ImmunizationsTab(immunizations)
                5 -> TreatmentPlansTab(treatmentPlans)
                6 -> ReferralsTab(referrals)
            }
        }
    }
}

@Composable
private fun PatientIdentityHeader(summary: HealthSummaryDto?) {
    val profile = summary?.profile
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BrandLightBlue)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                profile?.fullName?.takeIf { it.isNotBlank() } ?: "My chart",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BrandBlue
            )
            val mrn = profile?.medicalRecordNumber ?: profile?.mrn
            if (!mrn.isNullOrBlank()) {
                Text("MRN $mrn", style = MaterialTheme.typography.bodySmall)
            }
            val details = listOfNotNull(profile?.dateOfBirth?.let { "DOB ${it.take(10)}" }, profile?.gender, profile?.bloodType)
            if (details.isNotEmpty()) {
                Text(details.joinToString("  |  "), style = MaterialTheme.typography.bodySmall)
            }
            val primarySource = profile?.hospitalName
                ?: profile?.primaryHospitalName
                ?: profile?.hospitalId?.let { "Hospital ID $it" }
                ?: profile?.primaryHospitalId?.let { "Hospital ID $it" }
            primarySource?.takeIf { it.isNotBlank() }?.let {
                SourceText(listOf("Primary hospital: $it"))
            }
        }
    }
}

@Composable
private fun OverviewTab(summary: HealthSummaryDto?) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { StringListCard("Allergies", summary?.allergies ?: emptyList(), Icons.Default.Warning, "No known allergies") }
        item { StringListCard("Active diagnoses", summary?.activeDiagnoses ?: emptyList(), Icons.Default.MedicalInformation, "No active diagnoses") }
        item { StringListCard("Chronic conditions", summary?.chronicConditions ?: emptyList(), Icons.Default.Assignment, "No chronic conditions") }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun VitalsTab(vitals: List<VitalSignDto>) {
    if (vitals.isEmpty()) {
        EmptyState("No recent vitals")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lazyItems(vitals, key = { it.id }) { vital ->
            ExpandableClinicalCard(
                icon = Icons.Default.Favorite,
                sourceParts = listOfNotNull(vital.hospitalName ?: vital.hospitalId?.let { "Hospital ID $it" }, vital.recordedByName?.let { "Recorded by $it" }, vital.sourceDisplay),
                details = {
                    DetailGrid(
                        DetailItem("Blood pressure", vital.bloodPressureDisplay, Icons.Default.Favorite),
                        DetailItem("Heart rate", vital.heartRateDisplay, Icons.Default.MonitorHeart),
                        DetailItem("Temperature", vital.temperatureDisplay, Icons.Default.Thermostat),
                        DetailItem("Oxygen", vital.oxygenDisplay, Icons.Default.MonitorHeart),
                        DetailItem("Resp. rate", vital.respiratoryRateDisplay, Icons.Default.MonitorHeart),
                        DetailItem("Glucose", vital.bloodGlucoseDisplay, Icons.Default.Bloodtype),
                        DetailItem("Weight", vital.weightDisplay, Icons.Default.Scale),
                        DetailItem("Position", vital.bodyPosition, Icons.Default.Info),
                        DetailItem("Clinical flag", vital.clinicallySignificant?.let { if (it) "Significant" else "Not flagged" }, Icons.Default.Warning)
                    )
                    DetailNote("Notes", vital.notes)
                }
            ) {
                Text(vital.recordedDateDisplay, fontWeight = FontWeight.SemiBold)
                FlowText(listOfNotNull(vital.bloodPressureDisplay, vital.heartRateDisplay, vital.temperatureDisplay, vital.oxygenDisplay, vital.respiratoryRateDisplay, vital.bloodGlucoseDisplay, vital.weightDisplay))
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun LabsTab(labs: List<LabResultDto>) {
    if (labs.isEmpty()) {
        EmptyState("No recent lab results")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lazyItems(labs, key = { it.id }) { lab ->
            ExpandableClinicalCard(
                icon = Icons.Default.Science,
                sourceParts = listOfNotNull(lab.labName, lab.orderedBy?.let { "Ordered by $it" }),
                details = {
                    DetailGrid(
                        DetailItem("Result", listOfNotNull(lab.result, lab.unit).joinToString(" ").takeIf { it.isNotBlank() }, Icons.Default.Science),
                        DetailItem("Range", lab.referenceRange, Icons.Default.Info),
                        DetailItem("Status", lab.statusDisplay, Icons.Default.Warning),
                        DetailItem("Collected", lab.collectionDate?.take(10), Icons.Default.CalendarMonth),
                        DetailItem("Result date", lab.resultDate?.take(10), Icons.Default.CalendarMonth),
                        DetailItem("Ordered by", lab.orderedBy, Icons.Default.Person),
                        DetailItem("Laboratory", lab.labName, Icons.Default.LocalHospital)
                    )
                    DetailNote("Notes", lab.notes)
                }
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(lab.testName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(lab.statusDisplay, color = if (lab.isCritical) Color(0xFFB91C1C) else BrandBlue)
                }
                lab.result?.let { SecondaryText("${it} ${lab.unit ?: ""}".trim()) }
                lab.resultDate?.let { SecondaryText(it.take(10)) }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun MedicationsTab(medications: List<CurrentMedicationDto>) {
    if (medications.isEmpty()) {
        EmptyState("No active medications")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lazyItems(medications, key = { it.id }) { med ->
            ExpandableClinicalCard(
                icon = Icons.Default.Medication,
                sourceParts = listOfNotNull(med.prescribedBy?.let { "Prescribed by $it" }, med.indication),
                details = {
                    DetailGrid(
                        DetailItem("Medication", med.medicationName, Icons.Default.Medication),
                        DetailItem("Dosage", med.dosage, Icons.Default.Medication),
                        DetailItem("Frequency", med.frequency, Icons.Default.Info),
                        DetailItem("Status", med.status, Icons.Default.Warning),
                        DetailItem("Prescriber", med.prescribedBy, Icons.Default.Person),
                        DetailItem("Start", med.startDate?.take(10), Icons.Default.CalendarMonth),
                        DetailItem("End", med.endDate?.take(10), Icons.Default.CalendarMonth)
                    )
                    DetailNote("Indication", med.indication)
                }
            ) {
                Text(med.medicationName, fontWeight = FontWeight.SemiBold)
                FlowText(listOfNotNull(med.dosage, med.frequency, med.status))
                med.startDate?.let { SecondaryText("Start ${it.take(10)}") }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ImmunizationsTab(immunizations: List<ImmunizationDto>) {
    if (immunizations.isEmpty()) {
        EmptyState("No immunization records")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lazyItems(immunizations, key = { it.id }) { imm ->
            ExpandableClinicalCard(
                icon = Icons.Default.Vaccines,
                sourceParts = listOfNotNull(imm.administeredBy?.let { "Administered by $it" }),
                details = {
                    DetailGrid(
                        DetailItem("Vaccine", imm.vaccineName, Icons.Default.Vaccines),
                        DetailItem("Given", imm.administeredDate.take(10), Icons.Default.CalendarMonth),
                        DetailItem("By", imm.administeredBy, Icons.Default.Person),
                        DetailItem("Maker", imm.manufacturer, Icons.Default.LocalHospital),
                        DetailItem("Lot", imm.lotNumber, Icons.Default.Info),
                        DetailItem("Next dose", imm.nextDoseDate?.take(10) ?: imm.nextDueDate?.take(10), Icons.Default.CalendarMonth)
                    )
                    DetailNote("Notes", imm.notes)
                }
            ) {
                Text(imm.vaccineName, fontWeight = FontWeight.SemiBold)
                FlowText(listOfNotNull(imm.manufacturer, imm.administeredBy))
                SecondaryText(imm.administeredDate.take(10))
                imm.nextDoseDate?.let { SecondaryText("Next dose ${it.take(10)}") }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TreatmentPlansTab(treatmentPlans: List<TreatmentPlanDto>) {
    if (treatmentPlans.isEmpty()) {
        EmptyState("No treatment plans")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lazyItems(treatmentPlans, key = { it.id }) { plan ->
            ExpandableClinicalCard(
                icon = Icons.Default.Assignment,
                sourceParts = listOfNotNull(plan.createdBy?.let { "Created by $it" }),
                details = {
                    DetailGrid(
                        DetailItem("Status", plan.status, Icons.Default.Warning),
                        DetailItem("Start", plan.startDate?.take(10), Icons.Default.CalendarMonth),
                        DetailItem("End", plan.endDate?.take(10), Icons.Default.CalendarMonth),
                        DetailItem("Created by", plan.createdBy, Icons.Default.Person)
                    )
                    DetailNote("Description", plan.description)
                    plan.goals?.takeIf { it.isNotEmpty() }?.let { goals ->
                        DetailNote("Goals", goals.joinToString("\n"))
                    }
                }
            ) {
                Text(plan.title, fontWeight = FontWeight.SemiBold)
                plan.description?.let { SecondaryText(it) }
                FlowText(listOfNotNull(plan.status, plan.startDate?.take(10), plan.endDate?.take(10)))
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ReferralsTab(referrals: List<ReferralDto>) {
    if (referrals.isEmpty()) {
        EmptyState("No referrals")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lazyItems(referrals, key = { it.id }) { referral ->
            ExpandableClinicalCard(
                icon = Icons.Default.Description,
                sourceParts = listOfNotNull(referral.referredTo?.let { "Referred to $it" }, referral.specialistName),
                details = {
                    DetailGrid(
                        DetailItem("Type", referral.referralType, Icons.Default.Description),
                        DetailItem("Status", referral.status, Icons.Default.Warning),
                        DetailItem("Specialist", referral.specialistName, Icons.Default.Person),
                        DetailItem("Specialty", referral.specialty, Icons.Default.MedicalInformation),
                        DetailItem("Referred to", referral.referredTo, Icons.Default.LocalHospital),
                        DetailItem("Date", referral.referralDate?.take(10), Icons.Default.CalendarMonth)
                    )
                    DetailNote("Reason", referral.reason)
                    DetailNote("Notes", referral.notes)
                }
            ) {
                Text(referral.referralType ?: "Referral", fontWeight = FontWeight.SemiBold)
                FlowText(listOfNotNull(referral.specialistName, referral.specialty, referral.status))
                referral.reason?.let { SecondaryText(it) }
                referral.referralDate?.let { SecondaryText(it.take(10)) }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun StringListCard(title: String, values: List<String>, icon: ImageVector, emptyText: String) {
    ClinicalCard(icon) {
        Text(title, fontWeight = FontWeight.SemiBold)
        if (values.isEmpty()) {
            SecondaryText(emptyText)
        } else {
            values.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ClinicalCard(icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), color = BrandLightBlue, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

@Composable
private fun ExpandableClinicalCard(
    icon: ImageVector,
    sourceParts: List<String>,
    details: @Composable ColumnScope.() -> Unit,
    summary: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Surface(shape = RoundedCornerShape(8.dp), color = BrandLightBlue, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    summary()
                    SourceText(sourceParts)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse details" else "Read details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    details()
                }
            }
        }
    }
}

@Composable
private fun FlowText(values: List<String>) {
    val text = values.filter { it.isNotBlank() }.joinToString("  |  ")
    if (text.isNotBlank()) {
        SecondaryText(text)
    }
}

@Composable
private fun SourceText(values: List<String>) {
    val text = values.filter { it.isNotBlank() }.distinct().joinToString("  |  ")
    if (text.isNotBlank()) {
        Surface(shape = RoundedCornerShape(6.dp), color = BrandLightBlue) {
            Text(
                "Source: $text",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = BrandBlue,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private data class DetailItem(
    val label: String,
    val value: String?,
    val icon: ImageVector
)

@Composable
private fun DetailGrid(vararg items: DetailItem) {
    val visibleItems = items.filter { !it.value.isNullOrBlank() }
    if (visibleItems.isEmpty()) return

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .height(((visibleItems.size + 1) / 2 * 74).dp),
        state = rememberLazyGridState(),
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(visibleItems) { item ->
            DetailTile(item)
        }
    }
}

@Composable
private fun DetailTile(item: DetailItem) {
    Surface(shape = RoundedCornerShape(8.dp), color = BrandLightBlue.copy(alpha = 0.55f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(item.icon, null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.value.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            }
        }
    }
}

@Composable
private fun DetailNote(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.Description, null, tint = BrandBlue, modifier = Modifier.size(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SecondaryText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
