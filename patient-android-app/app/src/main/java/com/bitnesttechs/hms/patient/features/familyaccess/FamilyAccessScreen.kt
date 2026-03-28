package com.bitnesttechs.hms.patient.features.familyaccess

import androidx.compose.foundation.clickable
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
import androidx.navigation.NavController
import com.bitnesttechs.hms.patient.core.models.GrantProxyRequest
import com.bitnesttechs.hms.patient.core.models.ProxyResponse
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.SuccessGreen
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed
import com.bitnesttechs.hms.patient.ui.theme.WarningOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyAccessScreen(
    onBack: () -> Unit = {},
    navController: NavController? = null,
    viewModel: FamilyAccessViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showGrantSheet by remember { mutableStateOf(false) }
    var showDetailProxy by remember { mutableStateOf<ProxyResponse?>(null) }
    var revokeDialogProxy by remember { mutableStateOf<ProxyResponse?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.actionResult) {
        uiState.actionResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionResult()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Family Access") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandBlue,
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Default.Refresh, null, tint = Color.White)
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { showGrantSheet = true },
                    containerColor = BrandBlue
                ) {
                    Icon(Icons.Default.PersonAdd, "Grant Access", tint = Color.White)
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Tab row
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Granted by Me") },
                    icon = { Icon(Icons.Default.Share, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Access I Have") },
                    icon = { Icon(Icons.Default.People, null) }
                )
            }

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandBlue)
                }
                return@Column
            }

            val proxies = if (selectedTab == 0) uiState.grantedByMe else uiState.accessIHave

            if (proxies.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.People, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (selectedTab == 0) "No access granted to anyone"
                            else "No one has granted you access",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(proxies) { proxy ->
                    ProxyCard(
                        proxy = proxy,
                        isGrantor = selectedTab == 0,
                        onClick = { showDetailProxy = proxy },
                        onRevoke = if (selectedTab == 0) {
                            { revokeDialogProxy = proxy }
                        } else null
                    )
                }
            }
        }
    }

    // Grant bottom sheet
    if (showGrantSheet) {
        GrantProxyBottomSheet(
            onDismiss = { showGrantSheet = false },
            onGrant = { request ->
                viewModel.grantProxy(request)
                showGrantSheet = false
            }
        )
    }

    // Detail bottom sheet
    showDetailProxy?.let { proxy ->
        ProxyDetailSheet(
            proxy = proxy,
            isGrantor = selectedTab == 0,
            onDismiss = { showDetailProxy = null },
            onRevoke = if (selectedTab == 0) {
                { revokeDialogProxy = proxy; showDetailProxy = null }
            } else null,
            onPermissionClick = if (selectedTab == 1 && navController != null && proxy.grantorPatientId != null) {
                { permission ->
                    showDetailProxy = null
                    navController.navigate("proxy_data/${proxy.grantorPatientId}/$permission/${proxy.grantorName ?: "Patient"}")
                }
            } else null
        )
    }

    // Revoke confirmation dialog
    revokeDialogProxy?.let { proxy ->
        AlertDialog(
            onDismissRequest = { revokeDialogProxy = null },
            icon = { Icon(Icons.Default.Warning, null, tint = ErrorRed) },
            title = { Text("Revoke Access") },
            text = { Text("Are you sure you want to revoke access for ${proxy.granteeName ?: "this person"}?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.revokeProxy(proxy.id)
                        revokeDialogProxy = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) { Text("Revoke") }
            },
            dismissButton = {
                OutlinedButton(onClick = { revokeDialogProxy = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Proxy Card ────────────────────────────────────────────────────────────────

@Composable
fun ProxyCard(
    proxy: ProxyResponse,
    isGrantor: Boolean,
    onClick: () -> Unit,
    onRevoke: (() -> Unit)?
) {
    val statusColor = when (proxy.status?.uppercase()) {
        "ACTIVE" -> SuccessGreen
        "EXPIRED" -> Color.Gray
        "REVOKED" -> ErrorRed
        else -> WarningOrange
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Person,
                    null,
                    modifier = Modifier.size(40.dp),
                    tint = BrandBlue
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isGrantor) proxy.granteeName ?: "Unknown" else proxy.grantorName ?: "Unknown",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        proxy.relationship ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        proxy.status?.replaceFirstChar { it.uppercase() } ?: "Unknown",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (proxy.permissionsList.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    proxy.permissionsList.take(3).forEach { perm ->
                        AssistChip(
                            onClick = {},
                            label = { Text(perm.lowercase().replace("_", " "), style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(permissionIcon(perm), null, Modifier.size(14.dp))
                            }
                        )
                    }
                    if (proxy.permissionsList.size > 3) {
                        AssistChip(
                            onClick = {},
                            label = { Text("+${proxy.permissionsList.size - 3}") }
                        )
                    }
                }
            }

            if (onRevoke != null && proxy.status?.uppercase() == "ACTIVE") {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onRevoke,
                    colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
                ) {
                    Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Revoke")
                }
            }
        }
    }
}

fun permissionIcon(permission: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when (permission.uppercase()) {
        "VIEW_RECORDS" -> Icons.Default.Visibility
        "VIEW_APPOINTMENTS" -> Icons.Default.CalendarMonth
        "VIEW_MEDICATIONS" -> Icons.Default.Medication
        "VIEW_LAB_RESULTS" -> Icons.Default.Science
        "VIEW_BILLING" -> Icons.Default.Receipt
        "BOOK_APPOINTMENTS" -> Icons.Default.EventAvailable
        "MANAGE_MEDICATIONS" -> Icons.Default.MedicalServices
        else -> Icons.Default.Key
    }
}

// ── Grant Proxy Bottom Sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrantProxyBottomSheet(
    onDismiss: () -> Unit,
    onGrant: (GrantProxyRequest) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var selectedPermissions by remember { mutableStateOf(setOf<String>()) }
    var notes by remember { mutableStateOf("") }

    val allPermissions = listOf(
        "VIEW_RECORDS", "VIEW_APPOINTMENTS", "VIEW_MEDICATIONS",
        "VIEW_LAB_RESULTS", "BOOK_APPOINTMENTS", "MANAGE_MEDICATIONS"
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Grant Family Access", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Patient Username") },
                leadingIcon = { Icon(Icons.Default.Person, null) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = relationship,
                onValueChange = { relationship = it },
                label = { Text("Relationship (e.g. Spouse, Parent)") },
                leadingIcon = { Icon(Icons.Default.People, null) },
                modifier = Modifier.fillMaxWidth()
            )

            Text("Permissions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            allPermissions.forEach { perm ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = perm in selectedPermissions,
                        onCheckedChange = {
                            selectedPermissions = if (it) selectedPermissions + perm
                            else selectedPermissions - perm
                        }
                    )
                    Text(
                        perm.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Button(
                onClick = {
                    onGrant(
                        GrantProxyRequest(
                            granteeUsername = username,
                            relationship = relationship,
                            permissions = selectedPermissions.joinToString(","),
                            notes = notes.ifBlank { null }
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = username.isNotBlank() && relationship.isNotBlank() && selectedPermissions.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) {
                Icon(Icons.Default.PersonAdd, null)
                Spacer(Modifier.width(8.dp))
                Text("Grant Access")
            }
        }
    }
}

// ── Proxy Detail Bottom Sheet ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyDetailSheet(
    proxy: ProxyResponse,
    isGrantor: Boolean,
    onDismiss: () -> Unit,
    onRevoke: (() -> Unit)?,
    onPermissionClick: ((String) -> Unit)? = null
) {
    val statusColor = when (proxy.status?.uppercase()) {
        "ACTIVE" -> SuccessGreen
        "EXPIRED" -> Color.Gray
        "REVOKED" -> ErrorRed
        else -> WarningOrange
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, Modifier.size(48.dp), tint = BrandBlue)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (isGrantor) proxy.granteeName ?: "Unknown" else proxy.grantorName ?: "Unknown",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(proxy.relationship ?: "—", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(16.dp), color = statusColor.copy(alpha = 0.15f)) {
                    Text(
                        proxy.status?.replaceFirstChar { it.uppercase() } ?: "Unknown",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = statusColor, fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider()

            // Permissions
            Text("Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            proxy.permissionsList.forEach { perm ->
                val isClickable = onPermissionClick != null
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isClickable) Modifier.clickable { onPermissionClick?.invoke(perm) }
                            else Modifier
                        )
                        .padding(vertical = 8.dp)
                ) {
                    Icon(permissionIcon(perm), null, Modifier.size(20.dp), tint = BrandBlue)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        perm.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() },
                        modifier = Modifier.weight(1f)
                    )
                    if (isClickable) {
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider()

            // Timeline
            Text("Timeline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (proxy.grantedAt != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = SuccessGreen)
                    Spacer(Modifier.width(8.dp))
                    Text("Granted: ${proxy.grantedAt.take(10)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (proxy.expiresAt != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null, Modifier.size(16.dp), tint = WarningOrange)
                    Spacer(Modifier.width(8.dp))
                    Text("Expires: ${proxy.expiresAt.take(10)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (proxy.revokedAt != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cancel, null, Modifier.size(16.dp), tint = ErrorRed)
                    Spacer(Modifier.width(8.dp))
                    Text("Revoked: ${proxy.revokedAt.take(10)}", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (!proxy.notes.isNullOrBlank()) {
                HorizontalDivider()
                Text("Notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(proxy.notes, style = MaterialTheme.typography.bodyMedium)
            }

            if (onRevoke != null && proxy.status?.uppercase() == "ACTIVE") {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRevoke,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Icon(Icons.Default.Close, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Revoke Access")
                }
            }
        }
    }
}
