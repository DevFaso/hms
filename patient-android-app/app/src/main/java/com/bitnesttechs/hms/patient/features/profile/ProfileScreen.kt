package com.bitnesttechs.hms.patient.features.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bitnesttechs.hms.patient.core.models.PatientProfileDto
import com.bitnesttechs.hms.patient.core.models.PatientProfileUpdateDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavController? = null,
    viewModel: ProfileViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    val profile by viewModel.profile.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loggedOut by viewModel.loggedOut.collectAsState()
    val profileImageUrl by viewModel.profileImageUrl.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.uploadPhoto(context, it) }
    }

    // Editable fields
    var editPhone by remember(profile) { mutableStateOf(profile?.phoneNumberPrimary ?: "") }
    var editEmail by remember(profile) { mutableStateOf(profile?.email ?: "") }
    var editAddress by remember(profile) { mutableStateOf(profile?.addressLine1 ?: "") }
    var editCity by remember(profile) { mutableStateOf(profile?.city ?: "") }
    var editEmergencyName by remember(profile) { mutableStateOf(profile?.emergencyContactName ?: "") }
    var editEmergencyPhone by remember(profile) { mutableStateOf(profile?.emergencyContactPhone ?: "") }
    var editPharmacy by remember(profile) { mutableStateOf(profile?.preferredPharmacy ?: "") }

    val saveResult by viewModel.saveResult.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(saveResult) {
        saveResult?.let { snackbar.showSnackbar(it); viewModel.clearSaveResult() }
    }

    LaunchedEffect(loggedOut) { if (loggedOut) onLogout() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color(0xFFF5F7FA),
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandBlue, titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = {
                        if (isEditing) {
                            viewModel.updateProfile(
                                PatientProfileUpdateDto(
                                    phoneNumberPrimary = editPhone.ifBlank { null },
                                    email = editEmail.ifBlank { null },
                                    addressLine1 = editAddress.ifBlank { null },
                                    city = editCity.ifBlank { null },
                                    emergencyContactName = editEmergencyName.ifBlank { null },
                                    emergencyContactPhone = editEmergencyPhone.ifBlank { null },
                                    preferredPharmacy = editPharmacy.ifBlank { null }
                                )
                            )
                            isEditing = false
                        } else {
                            isEditing = true
                        }
                    }) {
                        Icon(
                            if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                            null, tint = Color.White
                        )
                    }
                }
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
                Card(
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.size(96.dp),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            val imageUrl = profileImageUrl
                            if (!imageUrl.isNullOrBlank()) {
                                val fullUrl = if (imageUrl.startsWith("http")) imageUrl
                                    else "https://api.hms.dev.bitnesttechs.com$imageUrl"
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(fullUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Profile photo",
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(CircleShape)
                                        .clickable { photoPickerLauncher.launch("image/*") },
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = BrandBlue,
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clickable { photoPickerLauncher.launch("image/*") }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            profile?.fullName?.take(1)?.uppercase() ?: "P",
                                            color = Color.White,
                                            style = MaterialTheme.typography.headlineLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            // Camera badge
                            Surface(
                                shape = CircleShape,
                                color = BrandBlue,
                                shadowElevation = 4.dp,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.CameraAlt, "Change photo",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            profile?.fullName ?: "—",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        profile?.let { p ->
                            (p.mrn ?: p.medicalRecordNumber)?.let {
                                Spacer(Modifier.height(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = BrandLightBlue
                                ) {
                                    Text(
                                        "MRN: $it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BrandBlue,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
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
                        if (isEditing) {
                            EditableProfileRow(Icons.Default.Phone, "Phone", editPhone) { editPhone = it }
                            EditableProfileRow(Icons.Default.Email, "Email", editEmail) { editEmail = it }
                            EditableProfileRow(Icons.Default.Home, "Address", editAddress) { editAddress = it }
                            EditableProfileRow(Icons.Default.LocationCity, "City", editCity) { editCity = it }
                        } else {
                            p.phone?.let { ProfileRow(Icons.Default.Phone, "Phone", it) }
                            p.email?.let { ProfileRow(Icons.Default.Email, "Email", it) }
                            p.address?.let { ProfileRow(Icons.Default.Home, "Address", it) }
                        }
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
            item { ProfileSection("Emergency Contact") }
            item {
                ProfileCard {
                    if (isEditing) {
                        EditableProfileRow(Icons.Default.ContactPhone, "Name", editEmergencyName) { editEmergencyName = it }
                        EditableProfileRow(Icons.Default.Phone, "Phone", editEmergencyPhone) { editEmergencyPhone = it }
                    } else {
                        profile?.emergencyContactName?.let {
                            ProfileRow(Icons.Default.ContactPhone, "Name", it)
                        }
                        profile?.emergencyContactPhone?.let {
                            ProfileRow(Icons.Default.Phone, "Phone", it)
                        }
                        if (profile?.emergencyContactName == null && profile?.emergencyContactPhone == null) {
                            Text("Not set", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Preferred Pharmacy
            item { ProfileSection("Pharmacy") }
            item {
                ProfileCard {
                    if (isEditing) {
                        EditableProfileRow(Icons.Default.LocalPharmacy, "Preferred Pharmacy", editPharmacy) { editPharmacy = it }
                    } else {
                        profile?.preferredPharmacy?.let {
                            ProfileRow(Icons.Default.LocalPharmacy, "Preferred", it)
                        } ?: Text("Not set", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Logout
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        width = 1.5.dp
                    )
                ) {
                    Icon(Icons.Default.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(24.dp))
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
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = BrandBlue,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun ProfileCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun ProfileRow(icon: ImageVector, label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = BrandLightBlue,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(18.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun EditableProfileRow(icon: ImageVector, label: String, value: String, onValueChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = BrandLightBlue,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(18.dp))
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(12.dp)
        )
    }
}
