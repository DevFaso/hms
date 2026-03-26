package com.bitnesttechs.hms.patient.features.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.PatientProfileDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    val profile by viewModel.profile.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loggedOut by viewModel.loggedOut.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(loggedOut) { if (loggedOut) onLogout() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandBlue, titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Avatar + name header
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = CircleShape, color = BrandBlue,
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                profile?.fullName?.take(1)?.uppercase() ?: "P",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(profile?.fullName ?: "—", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    profile?.mrn?.let {
                        Text("MRN: $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Personal information
            item { ProfileSection("Personal Information") }
            item {
                ProfileCard {
                    profile?.let { p ->
                        p.dateOfBirth?.let { ProfileRow(Icons.Default.Cake, "Date of Birth", it.take(10)) }
                        p.gender?.let { ProfileRow(Icons.Default.Person, "Gender", it) }
                        p.phone?.let { ProfileRow(Icons.Default.Phone, "Phone", it) }
                        p.email?.let { ProfileRow(Icons.Default.Email, "Email", it) }
                        p.address?.let { ProfileRow(Icons.Default.Home, "Address", it) }
                    } ?: Text("No profile data", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Medical info
            item { ProfileSection("Medical Information") }
            item {
                ProfileCard {
                    profile?.bloodType?.let { ProfileRow(Icons.Default.Bloodtype, "Blood Type", it) }
                    profile?.allergiesList?.takeIf { it.isNotEmpty() }?.let {
                        ProfileRow(Icons.Default.Warning, "Allergies", it.joinToString(", "))
                    }
                }
            }

            // Insurance
            profile?.insuranceProvider?.let { insurer ->
                item { ProfileSection("Insurance") }
                item {
                    ProfileCard {
                        ProfileRow(Icons.Default.Shield, "Provider", insurer)
                        profile?.insurancePolicyNumber?.let {
                            ProfileRow(Icons.Default.Badge, "Policy #", it)
                        }
                    }
                }
            }

            // Emergency contact
            profile?.emergencyContactName?.let { ecName ->
                item { ProfileSection("Emergency Contact") }
                item {
                    ProfileCard {
                        ProfileRow(Icons.Default.ContactPhone, "Name", ecName)
                        profile?.emergencyContactPhone?.let {
                            ProfileRow(Icons.Default.Phone, "Phone", it)
                        }
                    }
                }
            }

            // Logout
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Sign Out") },
            text = { Text("Are you sure you want to sign out?") },
            confirmButton = {
                TextButton(onClick = { showLogoutDialog = false; viewModel.logout() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProfileSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = BrandBlue)
}

@Composable
private fun ProfileCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun ProfileRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
