package com.bitnesttechs.hms.patient.features.appointments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.features.dashboard.StatusBadge
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentsScreen(viewModel: AppointmentsViewModel = hiltViewModel()) {
    val appointments by viewModel.appointments.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appointments") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }
        if (appointments.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No appointments found", style = MaterialTheme.typography.bodyLarge)
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(appointments) { appt ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                appt.doctorName ?: "Unknown Doctor",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            appt.departmentName?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("${appt.appointmentDate} ${appt.appointmentTime ?: ""}".trim(),
                                style = MaterialTheme.typography.bodySmall)
                            appt.type?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            appt.reason?.let {
                                Spacer(Modifier.height(4.dp))
                                Text("Reason: $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        StatusBadge(appt.statusDisplay)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
