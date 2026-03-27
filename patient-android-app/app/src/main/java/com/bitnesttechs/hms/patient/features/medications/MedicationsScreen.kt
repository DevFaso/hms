package com.bitnesttechs.hms.patient.features.medications

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationsScreen(onBack: () -> Unit = {}, viewModel: MedicationsViewModel = hiltViewModel()) {
    val medications by viewModel.medications.collectAsState()
    val prescriptions by viewModel.prescriptions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Medications", "Prescriptions")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Medications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = androidx.compose.ui.graphics.Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { idx, title ->
                    Tab(selected = selectedTab == idx, onClick = { selectedTab = idx },
                        text = { Text(title) })
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandBlue)
                }
                return@Column
            }

            when (selectedTab) {
                0 -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (medications.isEmpty()) item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No active medications")
                        }
                    }
                    items(medications) { med ->
                        Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(med.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    if (!med.isActive) {
                                        Surface(shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)) {
                                            Text("Inactive", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                med.dosage?.let { Text("Dosage: $it", style = MaterialTheme.typography.bodySmall) }
                                med.frequency?.let { Text("Frequency: $it", style = MaterialTheme.typography.bodySmall) }
                                med.prescribedBy?.let { Text("Prescribed by: $it", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                med.instructions?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Instructions: $it", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                1 -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (prescriptions.isEmpty()) item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No prescriptions")
                        }
                    }
                    items(prescriptions) { rx ->
                        Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(rx.medicationName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Surface(shape = RoundedCornerShape(50), color = BrandBlue.copy(alpha = 0.1f)) {
                                        Text("Refills: ${rx.refillsRemaining}",
                                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall, color = BrandBlue)
                                    }
                                }
                                rx.dosage?.let { Text("Dosage: $it", style = MaterialTheme.typography.bodySmall) }
                                rx.frequency?.let { Text("Frequency: $it", style = MaterialTheme.typography.bodySmall) }
                                rx.prescribedBy?.let { Text("Prescribed by: $it", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                rx.expiryDate?.let { Text("Expires: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}
