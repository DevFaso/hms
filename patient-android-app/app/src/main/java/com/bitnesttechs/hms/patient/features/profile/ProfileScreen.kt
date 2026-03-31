package com.bitnesttechs.hms.patient.features.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.locale.LocaleHelper
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
    var showLanguageDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Android system photo picker (no READ_MEDIA_IMAGES permission needed)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
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
                title = { Text(stringResource(R.string.profile)) },
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
                    colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF1C1B1F)),
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
                                        .clickable { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = BrandBlue,
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clickable { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
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
                                        stringResource(R.string.mrn_prefix, it),
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
            item { ProfileSection(stringResource(R.string.personal_information)) }
            item {
                ProfileCard {
                    profile?.let { p ->
                        p.dateOfBirth?.let { ProfileRow(Icons.Default.Cake, stringResource(R.string.date_of_birth), it.take(10)) }
                        p.gender?.let { ProfileRow(Icons.Default.Person, stringResource(R.string.gender), it) }
                        if (isEditing) {
                            EditableProfileRow(Icons.Default.Phone, stringResource(R.string.phone), editPhone) { editPhone = it }
                            EditableProfileRow(Icons.Default.Email, stringResource(R.string.email), editEmail) { editEmail = it }
                            EditableProfileRow(Icons.Default.Home, stringResource(R.string.address), editAddress) { editAddress = it }
                            EditableProfileRow(Icons.Default.LocationCity, stringResource(R.string.city), editCity) { editCity = it }
                        } else {
                            p.phone?.let { ProfileRow(Icons.Default.Phone, stringResource(R.string.phone), it) }
                            p.email?.let { ProfileRow(Icons.Default.Email, stringResource(R.string.email), it) }
                            p.address?.let { ProfileRow(Icons.Default.Home, stringResource(R.string.address), it) }
                        }
                    } ?: Text(stringResource(R.string.no_profile_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Medical info
            item { ProfileSection(stringResource(R.string.medical_information)) }
            item {
                ProfileCard {
                    profile?.bloodType?.let { ProfileRow(Icons.Default.Bloodtype, stringResource(R.string.blood_type), it) }
                    profile?.allergiesList?.takeIf { it.isNotEmpty() }?.let {
                        ProfileRow(Icons.Default.Warning, stringResource(R.string.allergies), it.joinToString(", "))
                    }
                }
            }

            // Insurance
            profile?.insuranceProvider?.let { insurer ->
                item { ProfileSection(stringResource(R.string.insurance)) }
                item {
                    ProfileCard {
                        ProfileRow(Icons.Default.Shield, stringResource(R.string.provider), insurer)
                        profile?.insurancePolicyNumber?.let {
                            ProfileRow(Icons.Default.Badge, stringResource(R.string.policy_number), it)
                        }
                    }
                }
            }

            // Emergency contact
            item { ProfileSection(stringResource(R.string.emergency_contact)) }
            item {
                ProfileCard {
                    if (isEditing) {
                        EditableProfileRow(Icons.Default.ContactPhone, stringResource(R.string.name_label), editEmergencyName) { editEmergencyName = it }
                        EditableProfileRow(Icons.Default.Phone, stringResource(R.string.phone), editEmergencyPhone) { editEmergencyPhone = it }
                    } else {
                        profile?.emergencyContactName?.let {
                            ProfileRow(Icons.Default.ContactPhone, stringResource(R.string.name_label), it)
                        }
                        profile?.emergencyContactPhone?.let {
                            ProfileRow(Icons.Default.Phone, stringResource(R.string.phone), it)
                        }
                        if (profile?.emergencyContactName == null && profile?.emergencyContactPhone == null) {
                            Text(stringResource(R.string.not_set), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Preferred Pharmacy
            item { ProfileSection(stringResource(R.string.pharmacy)) }
            item {
                ProfileCard {
                    if (isEditing) {
                        EditableProfileRow(Icons.Default.LocalPharmacy, stringResource(R.string.preferred_pharmacy), editPharmacy) { editPharmacy = it }
                    } else {
                        profile?.preferredPharmacy?.let {
                            ProfileRow(Icons.Default.LocalPharmacy, stringResource(R.string.preferred_pharmacy), it)
                        } ?: run {
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
                                        Icon(Icons.Default.LocalPharmacy, null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.preferred_pharmacy), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(stringResource(R.string.not_set), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FilledTonalButton(onClick = { isEditing = true }) {
                                    Text(stringResource(R.string.set_label), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            // Language
            item { ProfileSection(stringResource(R.string.language)) }
            item {
                ProfileCard {
                    val currentLang = LocaleHelper.getLanguage(context)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLanguageDialog = true }
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = BrandLightBlue,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Language, null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.language), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(LocaleHelper.getDisplayName(currentLang), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(stringResource(R.string.sign_out), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.sign_out)) },
            text = { Text(stringResource(R.string.sign_out_confirm)) },
            confirmButton = {
                TextButton(onClick = { showLogoutDialog = false; viewModel.logout() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.select_language)) },
            text = {
                Column {
                    LocaleHelper.supportedLanguages.forEach { langCode ->
                        val isSelected = langCode == LocaleHelper.getLanguage(context)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    LocaleHelper.setLanguage(context, langCode)
                                    showLanguageDialog = false
                                    // Recreate activity to apply locale
                                    (context as? android.app.Activity)?.recreate()
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null
                            )
                            Text(
                                LocaleHelper.getDisplayName(langCode),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
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
        colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF1C1B1F)),
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
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF1C1B1F)),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFF1C1B1F),
                unfocusedTextColor = Color(0xFF1C1B1F),
                focusedLabelColor = BrandBlue,
                unfocusedLabelColor = Color(0xFF757575),
                cursorColor = BrandBlue
            )
        )
    }
}
