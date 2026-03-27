package com.bitnesttechs.hms.patient.features.healthrecords

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
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthRecordsScreen(onBack: () -> Unit = {}, viewModel: HealthRecordsViewModel = hiltViewModel()) {
    val immunizations by viewModel.immunizations.collectAsState()
    val treatmentPlans by viewModel.treatmentPlans.collectAsState()
    val referrals by viewModel.referrals.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Immunizations", "Treatment Plans", "Referrals")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health Records") },
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index },
                        text = { Text(title, style = MaterialTheme.typography.labelSmall) })
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandBlue)
                }
                return@Column
            }

            when (selectedTab) {
                0 -> if (immunizations.isEmpty()) EmptyState("No immunization records") else
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(immunizations) { imm ->
                            Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(10.dp), color = BrandLightBlue,
                                        modifier = Modifier.size(44.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Vaccines, null, tint = BrandBlue,
                                                modifier = Modifier.size(22.dp))
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(imm.vaccineName, fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium)
                                        imm.manufacturer?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(imm.administeredDate.take(10),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    imm.nextDoseDate?.let {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("Next dose", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(it.take(10), style = MaterialTheme.typography.labelSmall,
                                                color = BrandBlue)
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }

                1 -> if (treatmentPlans.isEmpty()) EmptyState("No treatment plans") else
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(treatmentPlans) { tp ->
                            Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(tp.title, fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium)
                                    tp.description?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        tp.startDate?.let { sd ->
                                            Text("Start: ${sd.take(10)}", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        tp.endDate?.let {
                                            Text("End: ${it.take(10)}", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }

                2 -> if (referrals.isEmpty()) EmptyState("No referrals") else
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(referrals) { ref ->
                            Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(ref.referralType ?: "Referral", fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium)
                                    ref.specialistName?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    ref.reason?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    ref.referralDate?.let {
                                        Text(it.take(10), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
