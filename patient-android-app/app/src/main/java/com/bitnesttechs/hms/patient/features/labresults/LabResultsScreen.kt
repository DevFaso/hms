package com.bitnesttechs.hms.patient.features.labresults

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.LabResultDto
import com.bitnesttechs.hms.patient.core.models.LabResultStatus
import com.bitnesttechs.hms.patient.features.dashboard.StatusBadge
import com.bitnesttechs.hms.patient.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabResultsScreen(onBack: () -> Unit = {}, viewModel: LabResultsViewModel = hiltViewModel()) {
    val results by viewModel.results.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadFailed by viewModel.loadFailed.collectAsState()
    var selectedResult by remember { mutableStateOf<LabResultDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lab_results)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back),
                            tint = androidx.compose.ui.graphics.Color.White)
                    }
                },
                actions = {
                    // Without this the screen had no way to load twice, so the
                    // "could not refresh" banner below could never appear —
                    // `load()` only replaces the list from a non-null body, and
                    // the Retry in the empty state needs `loadFailed` already.
                    // DashboardScreen carries the same action.
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh),
                            tint = androidx.compose.ui.graphics.Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White)
            )
        }
    ) { padding ->
        // Only when there is nothing to show yet. Retrying from the banner
        // below sets isLoading, and a full-screen spinner here would blank the
        // stale results the banner exists to keep in front of the patient.
        if (isLoading && results.isEmpty()) {
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
            if (results.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Science, null, Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            // An empty list is not the same thing as a list
                            // that could not be loaded.
                            Text(
                                stringResource(
                                    if (loadFailed) R.string.load_failed else R.string.no_lab_results
                                ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (loadFailed) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { viewModel.load() }) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        }
                    }
                }
            }
            // With results already on screen the full-screen spinner is
            // suppressed, so without this row a refresh — whether it is running
            // or has just failed — produced no feedback at all: the empty state
            // never runs and the toolbar action looks inert.
            if (results.isNotEmpty() && (isLoading || loadFailed)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // Weighted: the French string is long enough to fill
                        // the row and collapse "Réessayer" to nothing.
                        Text(
                            stringResource(
                                if (isLoading) R.string.refreshing else R.string.refresh_failed
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isLoading) {
                            // In the banner, not over the list: a refresh must
                            // not blank the results it is refreshing.
                            CircularProgressIndicator(
                                color = BrandBlue,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            TextButton(onClick = { viewModel.load() }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }
            items(results) { lab ->
                val toneFill = lab.tone.badgeFill()
                val toneContent = lab.tone.onBadge()
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { selectedResult = lab },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            lab.isCritical -> CriticalRed.copy(alpha = 0.05f)
                            lab.isAbnormal -> WarningAmber.copy(alpha = 0.05f)
                            else -> MaterialTheme.colorScheme.surface
                        }
                    ),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        // A pending row is never a green tick: the lab has not
                        // released it and there is nothing to be reassured by.
                        // Nor is a released row whose status this build cannot
                        // name — UNKNOWN is exactly the case where the app does
                        // not know whether the value is normal.
                        //
                        // A released NORMAL row that nothing graded is not an
                        // all-clear either: resolveStatus falls through to
                        // statusOf(null) = NORMAL, so "graded normal" and
                        // "nothing graded this" are the same word on the wire.
                        // The tick, the green and the word "Normal" are all
                        // withheld for it — see LabResultDto.isGradedNormal and
                        // statusLabelRes, which reads "Reported" instead.
                        Icon(
                            when {
                                lab.isPending -> Icons.Default.HourglassEmpty
                                lab.displayStatus == LabResultStatus.UNKNOWN -> Icons.Default.HelpOutline
                                lab.isAbnormal || lab.isCritical -> Icons.Default.Warning
                                // Neutral rather than an all-clear when nothing
                                // graded the row.
                                lab.isGradedNormal -> Icons.Default.CheckCircle
                                else -> Icons.Default.Science
                            },
                            contentDescription = null,
                            tint = toneContent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(lab.testName, style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                StatusBadge(
                                    text = stringResource(lab.statusLabelRes),
                                    color = toneFill,
                                    contentColor = toneContent
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            if (lab.isPending) {
                                Text(stringResource(R.string.lab_result_pending),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                lab.valueWithUnit?.let {
                                    Text(stringResource(R.string.lab_result_with_value, it),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium)
                                }
                                lab.displayReferenceRange?.let {
                                    Text(stringResource(R.string.lab_reference_with_value, it),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (lab.referenceRangeUnitUncertain) {
                                    // The card is where the alarming
                                    // juxtaposition appears — "5.4 mmol/L"
                                    // directly above "70 - 110 mg/dL" — so the
                                    // caveat belongs here too, not only in the
                                    // dialog behind it.
                                    Text(stringResource(R.string.lab_reference_range_unit_uncertain),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            lab.displayDate?.let {
                                Text(stringResource(R.string.lab_date_with_value, it.take(10)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ChevronRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // Detail bottom sheet
    selectedResult?.let { lab ->
        LabResultDetailDialog(lab = lab, onDismiss = { selectedResult = null })
    }
}

@Composable
internal fun LabResultDetailDialog(lab: LabResultDto, onDismiss: () -> Unit) {
    val toneFill = lab.tone.badgeFill()
    val toneContent = lab.tone.onBadge()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(lab.testName, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.status_label_colon) + " ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusBadge(
                        text = stringResource(lab.statusLabelRes),
                        color = toneFill,
                        contentColor = toneContent
                    )
                }
                lab.testCode?.takeIf { it.isNotBlank() }?.let {
                    DetailRow(stringResource(R.string.test_code), it)
                }

                HorizontalDivider()

                // Result section
                Text(stringResource(R.string.lab_results_section),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (lab.isPending) {
                    // No value, no reference range and no interpretation: an
                    // unreleased result is redacted server-side and colouring
                    // it "normal" would tell the patient something untrue.
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.HourglassEmpty, null, tint = toneContent,
                            modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.lab_pending_explainer),
                            style = MaterialTheme.typography.bodySmall, color = toneContent)
                    }
                } else {
                    lab.valueWithUnit?.let { DetailRow(stringResource(R.string.lab_value), it) }
                    lab.displayReferenceRange?.let {
                        DetailRow(stringResource(R.string.reference_range), it)
                    }
                    if (lab.referenceRangeUnitUncertain) {
                        Text(stringResource(R.string.lab_reference_range_unit_uncertain),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Only when there IS an interpretation: a released row
                    // whose status this build cannot name has none, and an
                    // empty Row still costs a gap in the spacedBy column.
                    // "Within normal range" additionally needs a range to have
                    // been inside: resolveStatus falls through to
                    // statusOf(abnormalFlag) and statusOf(null) is NORMAL, so on
                    // a qualitative or ungraded row NORMAL means "nothing graded
                    // this", and a culture narrative must not be told it is
                    // within a range nobody configured.
                    if (lab.isCritical || lab.isAbnormal || lab.isGradedNormal) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            when {
                                lab.isCritical -> {
                                    Icon(Icons.Default.Warning, null, tint = toneContent,
                                        modifier = Modifier.size(16.dp))
                                    Text(stringResource(R.string.lab_interpretation_critical),
                                        style = MaterialTheme.typography.bodySmall, color = toneContent)
                                }
                                lab.isAbnormal -> {
                                    Icon(Icons.Default.Warning, null, tint = toneContent,
                                        modifier = Modifier.size(16.dp))
                                    Text(stringResource(R.string.lab_interpretation_abnormal),
                                        style = MaterialTheme.typography.bodySmall, color = toneContent)
                                }
                                lab.isGradedNormal -> {
                                    Icon(Icons.Default.CheckCircle, null, tint = toneContent,
                                        modifier = Modifier.size(16.dp))
                                    Text(stringResource(R.string.lab_interpretation_normal),
                                        style = MaterialTheme.typography.bodySmall, color = toneContent)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Dates
                Text(stringResource(R.string.lab_dates_section),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                // Labelled "Ordered", not "Collected": PatientLabResultServiceImpl
                // fills collectedAt from LabOrder.getOrderDatetime(), and LabOrder
                // carries no sample-collection timestamp at all. See the PR body.
                lab.collectedAt?.let { DetailRow(stringResource(R.string.ordered_at), it.take(10)) }
                // Not while pending: toResponse sets resultedAt BEFORE the
                // redaction early-return (fetchRows sorts on resultDate), so an
                // unreleased row still carries one — and printing "Resulted:
                // 22/09" two rows under "the laboratory has not released this
                // result yet" contradicts it.
                if (!lab.isPending) {
                    lab.resultedAt?.let { DetailRow(stringResource(R.string.resulted), it.take(10)) }
                }

                // Lab info
                lab.hospitalName?.let {
                    HorizontalDivider()
                    DetailRow(stringResource(R.string.laboratory), it)
                }

                // One divider for the pair: performedBy can arrive without
                // orderedBy (resolveStaffName returns null when the order has
                // no staff), and it used to land under the Dates section with
                // no separator at all.
                if (lab.orderedBy != null || lab.performedBy != null) {
                    HorizontalDivider()
                    lab.orderedBy?.let { DetailRow(stringResource(R.string.ordered_by), it) }
                    lab.performedBy?.let { DetailRow(stringResource(R.string.performed_by), it) }
                }

                lab.notes?.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    Text(stringResource(R.string.notes),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
