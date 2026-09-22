package com.bitnesttechs.hms.patient.features.medicalhistory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.FamilyHistoryEntry
import com.bitnesttechs.hms.patient.core.models.PatientDiagnosisSummary
import com.bitnesttechs.hms.patient.core.models.SocialHistory
import com.bitnesttechs.hms.patient.core.models.SurgicalHistoryEntry
import com.bitnesttechs.hms.patient.features.medicalhistory.MedicalHistoryViewModel.Section
import com.bitnesttechs.hms.patient.features.medicalhistory.MedicalHistoryViewModel.SectionState
import com.bitnesttechs.hms.patient.features.medicalhistory.MedicalHistoryViewModel.TobaccoStatus
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed

private val SurgicalPurple = Color(0xFF7C3AED)
private val FamilyGreen = Color(0xFF059669)
private val SocialAmber = Color(0xFFD97706)

/**
 * The web's My Medical History: four sections the care team keeps (read-only
 * here, as there) and under each a note that stays on this phone. Every
 * section has its own loading, error-with-retry and "nothing on record" state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicalHistoryScreen(
    onBack: () -> Unit,
    viewModel: MedicalHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.outcome) {
        state.outcome?.let { outcome ->
            val text = listOfNotNull(context.getString(outcome.resId), outcome.detail).joinToString(": ")
            viewModel.clearOutcome()
            snackbarHostState.showSnackbar(text)
        }
    }
    // One exit for the system back and the top-bar arrow: an open note is
    // saved and closed first (the write outlives this screen), then we leave.
    val leave = {
        state.editing?.let { viewModel.toggleNoteEdit(it) }
        onBack()
    }
    BackHandler(enabled = state.editing != null) { leave() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.medical_history_title)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
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
            section(
                title = R.string.mh_medical, icon = Icons.Default.MedicalServices, tint = BrandBlue,
                state = state.medical, emptyRes = R.string.mh_no_medical, plainEmpty = true,
                onRetry = { viewModel.loadMedical() }
            ) { DiagnosisRow(it) }
            notes(Section.MEDICAL, R.string.mh_personal_notes_medical, state, viewModel)

            section(
                title = R.string.mh_surgical, icon = Icons.Default.Healing, tint = SurgicalPurple,
                state = state.surgical, emptyRes = R.string.mh_no_surgical, plainEmpty = false,
                onRetry = { viewModel.loadSurgical() }
            ) { SurgeryRow(it) }
            notes(Section.SURGICAL, R.string.mh_personal_notes_surgical, state, viewModel)

            section(
                title = R.string.mh_family, icon = Icons.Default.FamilyRestroom, tint = FamilyGreen,
                state = state.family, emptyRes = R.string.mh_no_family, plainEmpty = false,
                onRetry = { viewModel.loadFamily() }
            ) { FamilyRow(it) }
            notes(Section.FAMILY, R.string.mh_personal_notes_family, state, viewModel)

            socialSection(state.social, onRetry = { viewModel.loadSocial() })
            notes(Section.SOCIAL, R.string.mh_personal_notes_social, state, viewModel)

            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ── Sections ─────────────────────────────────────────────────────────────

/** A list section: header, then loading / failed+retry / empty / rows. */
private fun <T> LazyListScope.section(
    title: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    state: SectionState<List<T>>,
    emptyRes: Int,
    plainEmpty: Boolean,
    onRetry: () -> Unit,
    row: @Composable (T) -> Unit
) {
    // Keys are stable across a section's states so an editor below keeps its identity (and focus) when the section resolves.
    item(key = "$title-header") { SectionHeader(stringResource(title), icon, tint) }
    val data = state.data
    when {
        state.loading -> item(key = "$title-loading") { LoadingRow() }
        state.failed -> item(key = "$title-failed") { FailedRow(onRetry) }
        data.isNullOrEmpty() -> item(key = "$title-empty") { EmptyRow(stringResource(emptyRes), plain = plainEmpty) }
        else -> item(key = "$title-rows") {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column {
                    data.forEachIndexed { index, entry ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { row(entry) }
                        if (index < data.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun LazyListScope.socialSection(state: SectionState<SocialHistory>, onRetry: () -> Unit) {
    item(key = "social-header") { SectionHeader(stringResource(R.string.mh_social), Icons.Default.Groups, SocialAmber) }
    val sh = state.data
    when {
        state.loading -> item(key = "social-loading") { LoadingRow() }
        state.failed -> item(key = "social-failed") { FailedRow(onRetry) }
        sh == null -> item(key = "social-empty") { EmptyRow(stringResource(R.string.mh_no_social), plain = false) }
        else -> {
            item(key = "social-smoking") { SmokingCard(sh) }
            item(key = "social-smokeless") { SmokelessCard(sh) }
            item(key = "social-alcohol") { AlcoholCard(sh) }
        }
    }
}

private fun LazyListScope.notes(
    section: Section,
    linkRes: Int,
    state: MedicalHistoryViewModel.UiState,
    viewModel: MedicalHistoryViewModel
) {
    // No identity to file notes under (see HistoryNotesStore): no notes this session.
    if (!state.notesAvailable) return
    item(key = "notes-${section.name}") {
        val editing = state.editing == section
        val text = state.notes[section] ?: ""
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.toggleNoteEdit(section) }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(linkRes), fontWeight = FontWeight.SemiBold)
                }
                Text(
                    stringResource(R.string.mh_notes_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (editing) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { viewModel.noteChanged(section, it) },
                        label = { Text(stringResource(R.string.mh_notes_label)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (text.isNotBlank()) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
                FilledTonalButton(onClick = { viewModel.toggleNoteEdit(section) }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (editing) R.string.save else R.string.add))
                }
            }
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LoadingRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = BrandBlue, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.medical_history_loading), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailedRow(onRetry: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, null, tint = ErrorRed)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.mh_section_load_failed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** The web shows medical history's empty state as a plain line, the others as a card with a clock. */
@Composable
private fun EmptyRow(text: String, plain: Boolean) {
    if (plain) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp))
    } else {
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Schedule, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

// ── Rows ─────────────────────────────────────────────────────────────────

@Composable
private fun DiagnosisRow(item: PatientDiagnosisSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(item.description ?: "", fontWeight = FontWeight.Medium)
        LabelValue(stringResource(R.string.date), MedicalHistoryDates.formatDateTime(item.diagnosedAt))
    }
}

@Composable
private fun SurgeryRow(item: SurgicalHistoryEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(item.procedureDisplay ?: "", fontWeight = FontWeight.Medium)
        LabelValue(stringResource(R.string.date), MedicalHistoryDates.formatDate(item.procedureDate))
        LabelValue(stringResource(R.string.mh_outcome), item.outcome ?: "-")
    }
}

@Composable
private fun FamilyRow(item: FamilyHistoryEntry) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Person, null, tint = FamilyGreen)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val title = listOfNotNull(item.relationship, item.relativeName?.takeIf { it.isNotBlank() }).joinToString(" — ")
            Text(title, fontWeight = FontWeight.Medium)
            Text(item.conditionDisplay ?: item.conditionCode ?: "-", style = MaterialTheme.typography.bodySmall)
            item.ageAtOnset?.let {
                Text("${stringResource(R.string.mh_age_at_onset)}: $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item.severity?.takeIf { it.isNotBlank() }?.let {
            AssistChip(onClick = {}, enabled = false, label = { Text(it) })
        }
    }
}

// ── Social history (the web's three cards) ───────────────────────────────

@Composable
private fun SocialCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SmokingCard(sh: SocialHistory) {
    SocialCard(stringResource(R.string.mh_smoking_tobacco)) {
        val status = when (MedicalHistoryViewModel.tobaccoStatus(sh)) {
            TobaccoStatus.FORMER -> R.string.mh_tobacco_former
            TobaccoStatus.CURRENT -> R.string.mh_tobacco_current
            TobaccoStatus.NEVER -> R.string.mh_tobacco_never
        }
        LabelValue(stringResource(R.string.mh_tobacco_use), stringResource(status))
        sh.tobaccoType?.takeIf { it.isNotBlank() }?.let { LabelValue(stringResource(R.string.mh_tobacco_types), it) }
        sh.tobaccoQuitDate?.takeIf { it.isNotBlank() }?.let { LabelValue(stringResource(R.string.mh_quit_date), MedicalHistoryDates.formatDate(it)) }
    }
}

@Composable
private fun SmokelessCard(sh: SocialHistory) {
    SocialCard(stringResource(R.string.mh_smokeless_tobacco)) {
        val value = if (sh.tobaccoUse == false) stringResource(R.string.mh_tobacco_never)
        else sh.tobaccoType?.takeIf { it.isNotBlank() } ?: "-"
        LabelValue(stringResource(R.string.mh_smokeless_use), value)
    }
}

@Composable
private fun AlcoholCard(sh: SocialHistory) {
    SocialCard(stringResource(R.string.mh_alcohol)) {
        val value = if (sh.alcoholUse == true) sh.alcoholFrequency?.takeIf { it.isNotBlank() } ?: stringResource(R.string.yes) else "-"
        LabelValue(stringResource(R.string.mh_alcohol_use), value)
        sh.alcoholDrinksPerWeek?.takeIf { it != 0 }?.let { LabelValue(stringResource(R.string.mh_drinks_per_week), it.toString()) }
    }
}
