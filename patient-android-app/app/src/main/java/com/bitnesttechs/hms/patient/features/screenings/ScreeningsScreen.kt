package com.bitnesttechs.hms.patient.features.screenings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.ProInstrumentView
import com.bitnesttechs.hms.patient.core.models.ProScreeningEntry
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed
import com.bitnesttechs.hms.patient.ui.theme.SuccessGreen
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The web's My Screenings: what is open now, the form itself, and what was answered before. No scores anywhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreeningsScreen(
    onBack: () -> Unit,
    viewModel: ScreeningsViewModel = hiltViewModel()
) {
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
    // Back inside the form returns to the list; while an answer is being sent it waits.
    BackHandler(enabled = state.active != null) { if (!state.submitting) viewModel.cancel() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.active?.name ?: stringResource(R.string.screenings)) },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.active != null) viewModel.cancel() else onBack() },
                        enabled = !state.submitting
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue, titleContentColor = Color.White)
            )
        }
    ) { padding ->
        when {
            state.active != null -> InstrumentForm(state, viewModel, Modifier.padding(padding))
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            state.failed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.CloudOff, null, Modifier.size(64.dp), tint = ErrorRed)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.screenings_load_failed), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { viewModel.load() }) { Text(stringResource(R.string.retry)) }
                }
            }
            else -> ScreeningsList(state, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ScreeningsList(state: ScreeningsViewModel.UiState, viewModel: ScreeningsViewModel, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.screenings_privacy_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.available.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.screenings_none_open_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.screenings_none_open_desc), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            item {
                SectionHeader(stringResource(R.string.screenings_open_now))
                Text(stringResource(R.string.screenings_open_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.available, key = { it.code }) { a ->
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(a.name ?: a.code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Button(onClick = { viewModel.start(a) }, colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)) {
                            Text(stringResource(R.string.start_screening))
                        }
                    }
                }
            }
        }
        if (state.history.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.your_answers)) }
            items(state.history, key = { it.id }) { HistoryRow(it) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun HistoryRow(entry: ProScreeningEntry) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (entry.careTeamAlerted) Icons.Default.NotificationsActive else Icons.Default.TaskAlt,
                null, tint = if (entry.careTeamAlerted) ErrorRed else SuccessGreen
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.instrumentName ?: entry.instrumentCode ?: "", fontWeight = FontWeight.Medium)
                Text(
                    stringResource(
                        when {
                            entry.careTeamAlerted -> R.string.care_team_alerted
                            entry.followUpPlanned -> R.string.follow_up_planned
                            else -> R.string.screening_recorded
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                entry.administeredAt?.let {
                    Text(formatDateTime(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** "2026-09-21T10:15:00" in the device's short date-time style; the raw text when it does not parse. */
private fun formatDateTime(iso: String): String = runCatching {
    LocalDateTime.parse(iso.take(19)).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()))
}.getOrDefault(iso)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstrumentForm(state: ScreeningsViewModel.UiState, viewModel: ScreeningsViewModel, modifier: Modifier) {
    val instrument = state.instrument
    when {
        state.instrumentLoading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BrandBlue)
        }
        state.instrumentFailed || instrument == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Default.CloudOff, null, Modifier.size(64.dp), tint = ErrorRed)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.instrument_load_failed), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = { viewModel.retryInstrument() }) { Text(stringResource(R.string.retry)) }
            }
        }
        else -> InstrumentItems(instrument, state, viewModel, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstrumentItems(instrument: ProInstrumentView, state: ScreeningsViewModel.UiState, viewModel: ScreeningsViewModel, modifier: Modifier) {
    val answered = instrument.items.count { it.itemNo in state.answers }
    var languageMenu by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (instrument.availableLanguages.size > 1) {
                    ExposedDropdownMenuBox(expanded = languageMenu, onExpandedChange = { if (!state.submitting) languageMenu = !languageMenu }) {
                        OutlinedTextField(
                            value = instrument.language ?: state.language,
                            onValueChange = {},
                            readOnly = true,
                            enabled = !state.submitting,
                            label = { Text(stringResource(R.string.language)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(languageMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                            instrument.availableLanguages.forEach { lang ->
                                DropdownMenuItem(text = { Text(lang) }, onClick = { languageMenu = false; viewModel.changeLanguage(lang) })
                            }
                        }
                    }
                }
                Text(stringResource(R.string.screenings_privacy_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                instrument.instruction?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.screening_progress, answered, instrument.items.size),
                    style = MaterialTheme.typography.labelLarge, color = BrandBlue)
            }
        }
        items(instrument.items, key = { it.itemNo }) { item ->
            val chosen = state.answers[item.itemNo]
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = if (state.submitError != null && chosen == null)
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${item.itemNo}. ${item.prompt ?: ""}", fontWeight = FontWeight.Medium)
                    item.options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = chosen == option.optionNo, role = Role.RadioButton,
                                    enabled = !state.submitting,
                                    onClick = { viewModel.answer(item.itemNo, option.optionNo) })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = chosen == option.optionNo, onClick = null, enabled = !state.submitting)
                            Spacer(Modifier.width(8.dp))
                            Text(option.label ?: "", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.submitError?.let { err ->
                    Text(
                        if (err.resId == R.string.screening_incomplete) stringResource(err.resId, err.detail ?: "")
                        else listOfNotNull(stringResource(err.resId), err.detail).joinToString(": "),
                        color = ErrorRed, style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    onClick = { viewModel.submit() },
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sending))
                    } else {
                        Text(stringResource(R.string.send_my_answers), fontWeight = FontWeight.SemiBold)
                    }
                }
                instrument.sourceCitation?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
