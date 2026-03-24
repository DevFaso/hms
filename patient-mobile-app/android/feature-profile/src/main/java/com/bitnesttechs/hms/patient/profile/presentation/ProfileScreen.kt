package com.bitnesttechs.hms.patient.profile.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        when {
            state.isLoading -> HmsLoadingView()
            state.errorMessage != null -> HmsErrorView(state.errorMessage!!) { viewModel.load() }
            state.profile != null -> {
                val p = state.profile!!
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Avatar header
                    HmsCard {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = HmsPrimary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(p.fullName, style = MaterialTheme.typography.headlineMedium)
                            p.mrn?.let {
                                Text("MRN: $it", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                            }
                        }
                    }

                    // Personal info
                    HmsSectionHeader(title = "Personal Information")
                    HmsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileRow("Date of Birth", p.dateOfBirth ?: "—")
                            HorizontalDivider(color = HmsDivider)
                            ProfileRow("Gender", p.gender ?: "—")
                            HorizontalDivider(color = HmsDivider)
                            ProfileRow("Blood Type", p.bloodType ?: "—")
                        }
                    }

                    // Contact info
                    HmsSectionHeader(title = "Contact Information")
                    HmsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileRow("Email", p.email ?: "—")
                            HorizontalDivider(color = HmsDivider)
                            ProfileRow("Phone", p.phone ?: "—")
                            HorizontalDivider(color = HmsDivider)
                            ProfileRow("Address", p.address ?: "—")
                        }
                    }

                    // Emergency contact
                    HmsSectionHeader(title = "Emergency Contact")
                    HmsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileRow("Name", p.emergencyContactName ?: "—")
                            HorizontalDivider(color = HmsDivider)
                            ProfileRow("Phone", p.emergencyContactPhone ?: "—")
                            HorizontalDivider(color = HmsDivider)
                            ProfileRow("Relationship", p.emergencyContactRelation ?: "—")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = HmsTextPrimary)
    }
}
