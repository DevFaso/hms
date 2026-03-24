package com.bitnesttechs.hms.patient.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.bitnesttechs.hms.patient.core.designsystem.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(navController: NavController, onLogout: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("More") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HmsSectionHeader(title = "Health")
            MoreItem(Icons.Default.Medication, "Medications", HmsAccent) {
                navController.navigate("medications")
            }
            MoreItem(Icons.Default.Science, "Lab Results", HmsWarning) {
                navController.navigate("lab-results")
            }
            MoreItem(Icons.Default.MonitorHeart, "Vitals", HmsSuccess) {
                navController.navigate("vitals")
            }
            MoreItem(Icons.Default.MedicalServices, "Care Team", HmsPrimary) {
                navController.navigate("care-team")
            }
            MoreItem(Icons.Default.Vaccines, "Immunizations", HmsInfo) {
                navController.navigate("immunizations")
            }
            MoreItem(Icons.Default.ListAlt, "Treatment Plans", HmsSuccess) {
                navController.navigate("treatment-plans")
            }
            MoreItem(Icons.Default.CompareArrows, "Referrals", HmsWarning) {
                navController.navigate("referrals")
            }
            MoreItem(Icons.Default.EventNote, "Consultations", HmsPrimary) {
                navController.navigate("consultations")
            }
            MoreItem(Icons.Default.Description, "Documents", HmsAccent) {
                navController.navigate("documents")
            }

            Spacer(modifier = Modifier.height(8.dp))
            HmsSectionHeader(title = "Communication")
            MoreItem(Icons.Default.Chat, "Messages", HmsInfo) {
                navController.navigate("chat")
            }
            MoreItem(Icons.Default.Notifications, "Notifications", HmsError) {
                navController.navigate("notifications")
            }

            Spacer(modifier = Modifier.height(8.dp))
            HmsSectionHeader(title = "Account")
            MoreItem(Icons.Default.Person, "Profile", HmsPrimary) {
                navController.navigate("profile")
            }
            MoreItem(Icons.Default.Settings, "Settings", HmsTextSecondary) {
                navController.navigate("settings")
            }
            MoreItem(Icons.Default.Shield, "Privacy & Sharing", HmsAccent) {
                navController.navigate("consents")
            }
            MoreItem(Icons.Default.History, "Access Log", HmsWarning) {
                navController.navigate("access-logs")
            }
            MoreItem(Icons.Default.Help, "Help & Support", HmsTextTertiary) {}
        }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, color: Color, onClick: () -> Unit) {
    HmsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, color = HmsTextPrimary)
        }
    }
}
