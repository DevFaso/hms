package com.bitnesttechs.hms.patient.features.sharingprivacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.DisclosureEntryDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed
import com.bitnesttechs.hms.patient.ui.theme.WarningOrange
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The web's "Who accessed my record": the sharing opt-out on top, then the
 * accounting of disclosures with the whole-history counts leading the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingPrivacyScreen(onBack: () -> Unit = {}, viewModel: SharingPrivacyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.outcome) {
        state.outcome?.let { outcome ->
            val text = listOfNotNull(context.getString(outcome.resId), outcome.detail).joinToString(": ")
            viewModel.clearOutcome()
            scope.launch { snackbarHostState.showSnackbar(text) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sharing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue, titleContentColor = Color.White)
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { OptOutCard(state, viewModel) }

            item {
                Text(
                    stringResource(R.string.sharing_access_log_title),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // Shown above every state, including the empty one: routine chart
                // reads emit no audit event, so listing only disclosures without
                // saying so reads as "nobody else looked".
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.sharing_access_log_scope_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            when {
                state.logLoading -> item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = BrandBlue, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.sharing_loading_log), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.logFailed -> item {
                    StateCard(
                        icon = Icons.Default.CloudOff, tint = ErrorRed,
                        title = stringResource(R.string.sharing_log_failed_title),
                        body = stringResource(R.string.sharing_log_failed_desc)
                    ) {
                        FilledTonalButton(onClick = { viewModel.loadAccessLog() }) { Text(stringResource(R.string.sharing_log_retry)) }
                    }
                }
                state.entries.isEmpty() -> item {
                    StateCard(
                        icon = Icons.Default.Shield, tint = BrandBlue,
                        title = stringResource(R.string.sharing_no_access_title),
                        body = stringResource(R.string.sharing_no_access_desc)
                    )
                }
                else -> {
                    if (state.emergencyCount > 0 || state.externalCount > 0) {
                        item { AccessSummary(state.emergencyCount, state.externalCount) }
                    }
                    items(state.entries, key = { it.id }) { entry -> DisclosureRow(entry) }
                    if (state.hasMore || state.loadMoreFailed) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                when {
                                    state.loadingMore -> CircularProgressIndicator(color = BrandBlue, modifier = Modifier.size(24.dp))
                                    state.loadMoreFailed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(stringResource(R.string.sharing_load_more_failed), style = MaterialTheme.typography.bodySmall, color = ErrorRed)
                                        TextButton(onClick = { viewModel.loadMore() }) { Text(stringResource(R.string.retry)) }
                                    }
                                    else -> TextButton(onClick = { viewModel.loadMore() }) { Text(stringResource(R.string.sharing_load_more)) }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (state.showOptOutForm) {
        OptOutSheet(state, viewModel)
    }
}

/* ── Opt-out ── */

@Composable
private fun OptOutCard(state: SharingPrivacyViewModel.UiState, viewModel: SharingPrivacyViewModel) {
    val optedOut = state.optedOut
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (optedOut) WarningOrange.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    if (optedOut) Icons.Default.Lock else Icons.Default.LockOpen, null,
                    tint = if (optedOut) WarningOrange else BrandBlue, modifier = Modifier.size(28.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.sharing_opt_out_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.sharing_opt_out_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                state.optOutLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = BrandBlue, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.sharing_opt_out_loading), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.optOutFailed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, Modifier.size(16.dp), tint = ErrorRed)
                        Text(stringResource(R.string.sharing_opt_out_load_failed), style = MaterialTheme.typography.bodySmall, color = ErrorRed)
                    }
                    TextButton(onClick = { viewModel.loadOptOut() }) { Text(stringResource(R.string.retry)) }
                }
                else -> {
                    val optedOutAt = state.optOut?.optedOutAt
                    Text(
                        if (optedOut) stringResource(R.string.sharing_opt_out_status_on, formatDate(optedOutAt))
                        else stringResource(R.string.sharing_opt_out_status_off),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                    )
                    if (optedOut) {
                        OutlinedButton(onClick = { viewModel.revokeOptOut() }, enabled = !state.optOutSaving) {
                            if (state.optOutSaving) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.sharing_opt_out_disable))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.openOptOutForm() }, enabled = !state.optOutSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                        ) { Text(stringResource(R.string.sharing_opt_out_enable)) }
                    }
                    Text(stringResource(R.string.sharing_opt_out_own_hospital_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The web's opt-out form: an optional reason, then "Stop sharing". Dismissal waits while the request is out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptOutSheet(state: SharingPrivacyViewModel.UiState, viewModel: SharingPrivacyViewModel) {
    val isSaving by rememberUpdatedState(state.optOutSaving)
    val keepWhileSaving = remember { { _: SheetValue -> !isSaving } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = keepWhileSaving)

    ModalBottomSheet(
        onDismissRequest = { viewModel.cancelOptOutForm() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !state.optOutSaving)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.sharing_opt_out_enable), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.sharing_opt_out_desc), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.optOutReason,
                onValueChange = { viewModel.updateOptOutReason(it) },
                label = { Text(stringResource(R.string.sharing_opt_out_reason_label)) },
                placeholder = { Text(stringResource(R.string.sharing_opt_out_reason_placeholder)) },
                supportingText = { Text("${state.optOutReason.length} / ${SharingPrivacyViewModel.REASON_MAX}") },
                minLines = 3, maxLines = 6,
                enabled = !state.optOutSaving,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { viewModel.cancelOptOutForm() }, enabled = !state.optOutSaving) {
                    Text(stringResource(R.string.sharing_opt_out_cancel))
                }
                Button(
                    onClick = { viewModel.confirmOptOut() }, enabled = !state.optOutSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    if (state.optOutSaving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.sharing_opt_out_confirm))
                }
            }
        }
    }
}

/* ── Access log ── */

/** Whole-history counts of the two rows a patient opens this page to find. */
@Composable
private fun AccessSummary(emergency: Long, external: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (emergency > 0) {
            SummaryStat(Modifier.weight(1f), emergency, stringResource(R.string.sharing_summary_emergency), ErrorRed)
        }
        if (external > 0) {
            SummaryStat(Modifier.weight(1f), external, stringResource(R.string.sharing_summary_external), WarningOrange)
        }
    }
}

@Composable
private fun SummaryStat(modifier: Modifier, count: Long, label: String, tint: Color) {
    Card(modifier, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tint)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DisclosureRow(entry: DisclosureEntryDto) {
    val emergency = entry.category == SharingPrivacyViewModel.CATEGORY_EMERGENCY
    val tint = when {
        emergency -> ErrorRed
        entry.externalDisclosure -> WarningOrange
        else -> BrandBlue
    }
    Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(categoryIcon(entry.category), null, tint = tint, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.actor ?: stringResource(R.string.sharing_actor_unknown), fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium)
                val parts = listOfNotNull(
                    stringResource(categoryLabel(entry.category)),
                    roleLabel(entry.actorRole),
                    entry.hospitalName?.takeIf { it.isNotBlank() }
                )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = if (emergency) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant)
                entry.timestamp?.let {
                    Text(formatDateTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StateCard(icon: ImageVector, tint: Color, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(40.dp), tint = tint)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.let { Spacer(Modifier.height(4.dp)); it() }
        }
    }
}

private fun categoryIcon(category: String?): ImageVector = when (category) {
    "EMERGENCY_ACCESS" -> Icons.Default.Emergency
    "SHARED_WITH_PROVIDER" -> Icons.Default.Share
    "INSURANCE" -> Icons.Default.AccountBalance
    "COPY_RELEASED" -> Icons.Default.Download
    "IDENTITY_CHANGE" -> Icons.Default.CallMerge
    else -> Icons.Default.Visibility
}

/** An unknown or absent category gets the neutral label, never the raw enum name. */
private fun categoryLabel(category: String?): Int = when (category) {
    "EMERGENCY_ACCESS" -> R.string.sharing_category_emergency_access
    "TREATMENT_ACCESS" -> R.string.sharing_category_treatment_access
    "SHARED_WITH_PROVIDER" -> R.string.sharing_category_shared_with_provider
    "INSURANCE" -> R.string.sharing_category_insurance
    "COPY_RELEASED" -> R.string.sharing_category_copy_released
    "IDENTITY_CHANGE" -> R.string.sharing_category_identity_change
    else -> R.string.sharing_category_unknown
}

/** The web's bareRole + enumLabel('role'): strip `ROLE_`, translate the known roles, humanise the rest. */
@Composable
private fun roleLabel(raw: String?): String? {
    val bare = raw?.trim()?.removePrefix("ROLE_")?.takeIf { it.isNotBlank() && it != "UNKNOWN" } ?: return null
    val resId = when (bare.uppercase(Locale.ROOT)) {
        "ACCOUNTANT" -> R.string.role_accountant
        "ADMIN" -> R.string.role_admin
        "ANESTHESIOLOGIST" -> R.string.role_anesthesiologist
        "BILLING_SPECIALIST" -> R.string.role_billing_specialist
        "CLAIMS_REVIEWER" -> R.string.role_claims_reviewer
        "DOCTOR" -> R.string.role_doctor
        "HOSPITAL_ADMIN" -> R.string.role_hospital_admin
        "LAB_DIRECTOR" -> R.string.role_lab_director
        "LAB_MANAGER" -> R.string.role_lab_manager
        "LAB_SCIENTIST" -> R.string.role_lab_scientist
        "LAB_TECHNICIAN" -> R.string.role_lab_technician
        "MIDWIFE" -> R.string.role_midwife
        "NURSE" -> R.string.role_nurse
        "PATIENT" -> R.string.role_patient
        "PHARMACIST" -> R.string.role_pharmacist
        "PHARMACY_VERIFIER" -> R.string.role_pharmacy_verifier
        "PHYSICIAN" -> R.string.role_physician
        "PHYSIOTHERAPIST" -> R.string.role_physiotherapist
        "QUALITY_MANAGER" -> R.string.role_quality_manager
        "RADIOLOGIST" -> R.string.role_radiologist
        "RECEPTIONIST" -> R.string.role_receptionist
        "STAFF" -> R.string.role_staff
        "SUPER_ADMIN" -> R.string.role_super_admin
        "SURGEON" -> R.string.role_surgeon
        "TECHNICIAN" -> R.string.role_technician
        "THERAPIST" -> R.string.role_therapist
        "USER" -> R.string.role_user
        else -> null
    }
    return if (resId != null) stringResource(resId)
    else bare.replace('_', ' ').lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/** "2026-09-21T10:15:00" in the device's medium date + short time style; the raw text when it does not parse. */
private fun formatDateTime(iso: String): String = runCatching {
    LocalDateTime.parse(iso.take(19)).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()))
}.getOrDefault(iso)

private fun formatDate(iso: String?): String = iso?.let { text ->
    runCatching {
        LocalDateTime.parse(text.take(19)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
    }.getOrDefault(text.take(10))
} ?: ""
