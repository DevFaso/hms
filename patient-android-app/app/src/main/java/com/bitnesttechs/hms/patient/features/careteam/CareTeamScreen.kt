package com.bitnesttechs.hms.patient.features.careteam

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.CareTeamMemberDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareTeamScreen(onBack: () -> Unit = {}, viewModel: CareTeamViewModel = hiltViewModel()) {
    val careTeam by viewModel.careTeam.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Care Team") },
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
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Primary physician
            careTeam?.primaryPhysician?.let { primary ->
                item {
                    Text("Primary Physician", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                }
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(2.dp),
                        colors = CardDefaults.cardColors(containerColor = BrandLightBlue)
                    ) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = BrandBlue,
                                modifier = Modifier.size(52.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        primary.name.take(1).uppercase(),
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(primary.name, fontWeight = FontWeight.Bold)
                                Text(primary.specialty ?: "Physician",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                primary.phone?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall,
                                        color = BrandBlue)
                                }
                            }
                            primary.phone?.let { phone ->
                                IconButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                                }) {
                                    Icon(Icons.Default.Phone, "Call", tint = BrandBlue)
                                }
                            }
                        }
                    }
                }
            }

            // Other members
            val members = careTeam?.members?.filter { it.id != careTeam?.primaryPhysician?.id }
            if (!members.isNullOrEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Care Team Members", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                }
                items(members) { member ->
                    CareTeamMemberRow(member) { phone ->
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                    }
                }
            }

            if (careTeam == null && !isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No care team information available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun CareTeamMemberRow(member: CareTeamMemberDto, onCallPhone: (String) -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(member.name.take(1).uppercase(), fontWeight = FontWeight.Bold,
                        color = BrandBlue)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(member.name, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                Text(member.role ?: member.specialty ?: "Staff",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                member.department?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            member.phone?.let { phone ->
                IconButton(onClick = { onCallPhone(phone) }) {
                    Icon(Icons.Default.Phone, "Call", tint = BrandBlue, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
