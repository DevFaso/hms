package com.bitnesttechs.hms.patient.careteam.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import com.bitnesttechs.hms.patient.careteam.data.CareTeamMemberDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareTeamScreen(viewModel: CareTeamViewModel = hiltViewModel()) {
    val members by viewModel.members.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var selectedMember by remember { mutableStateOf<CareTeamMemberDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Care Team") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading && members.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null && members.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "Error", color = HmsError)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadCareTeam() }) { Text("Retry") }
                    }
                }
                members.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MedicalServices, null, tint = HmsTextTertiary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Care Team", style = MaterialTheme.typography.titleMedium, color = HmsTextSecondary)
                    }
                }
                else -> {
                    if (selectedMember != null) {
                        MemberDetail(selectedMember!!) { selectedMember = null }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(members) { member ->
                                MemberCard(member) { selectedMember = member }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberCard(member: CareTeamMemberDto, onClick: () -> Unit) {
    HmsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(HmsPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(member.initials, style = MaterialTheme.typography.titleSmall, color = HmsPrimary)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(member.displayName, style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary)
                member.specialty?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsAccent) }
                member.role?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
            }
        }
    }
}

@Composable
private fun MemberDetail(member: CareTeamMemberDto, onBack: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("← Back") } }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape).background(HmsPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(member.initials, style = MaterialTheme.typography.headlineMedium, color = HmsPrimary)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(member.displayName, style = MaterialTheme.typography.headlineSmall, color = HmsTextPrimary)
                member.specialty?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = HmsAccent) }
            }
        }
        val details = listOfNotNull(
            member.role?.let { "Role" to it },
            member.department?.let { "Department" to it },
            member.hospitalName?.let { "Hospital" to it },
            member.phone?.let { "Phone" to it },
            member.email?.let { "Email" to it },
        )
        items(details) { (label, value) ->
            HmsCard {
                Column {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
                    Text(value, style = MaterialTheme.typography.bodyLarge, color = HmsTextPrimary)
                }
            }
        }
    }
}
