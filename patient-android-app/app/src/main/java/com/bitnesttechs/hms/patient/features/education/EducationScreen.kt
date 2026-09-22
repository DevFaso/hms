package com.bitnesttechs.hms.patient.features.education

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.EducationItemDto
import com.bitnesttechs.hms.patient.core.models.EducationQuestionDto
import com.bitnesttechs.hms.patient.features.dashboard.StatusBadge
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed
import com.bitnesttechs.hms.patient.ui.theme.SuccessGreen
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The web's My Education: To read / Completed / My questions, a reader, and an ask sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EducationScreen(
    onBack: () -> Unit,
    viewModel: EducationViewModel = hiltViewModel()
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
    // Back inside the reader returns to the list.
    BackHandler(enabled = state.reading != null) { viewModel.closeReader() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.reading?.title ?: stringResource(R.string.education)) },
                navigationIcon = {
                    IconButton(onClick = { if (state.reading != null) viewModel.closeReader() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue, titleContentColor = Color.White),
                actions = {
                    if (state.reading == null) {
                        TextButton(onClick = { viewModel.openAsk(null) }) {
                            Text(stringResource(R.string.ask_question), color = Color.White)
                        }
                    }
                }
            )
        }
    ) { padding ->
        val reading = state.reading
        when {
            reading != null -> Reader(reading, state, viewModel, Modifier.padding(padding))
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            state.failed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.CloudOff, null, Modifier.size(64.dp), tint = ErrorRed)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.education_load_failed), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { viewModel.load() }) { Text(stringResource(R.string.retry)) }
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                val tabs = EducationViewModel.Tab.entries
                TabRow(selectedTabIndex = tabs.indexOf(state.tab)) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = state.tab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            text = { Text(stringResource(tab.titleRes)) }
                        )
                    }
                }
                when (state.tab) {
                    EducationViewModel.Tab.ASSIGNED -> ItemList(state.assigned, state.warningSigns, R.string.education_empty_title, R.string.education_empty_desc, viewModel)
                    EducationViewModel.Tab.COMPLETED -> ItemList(state.completed, emptyList(), R.string.education_no_completed, null, viewModel)
                    EducationViewModel.Tab.QUESTIONS -> QuestionList(state, viewModel)
                }
            }
        }
    }

    if (state.askOpen) {
        AskSheet(state, viewModel)
    }
}

private val EducationViewModel.Tab.titleRes: Int get() = when (this) {
    EducationViewModel.Tab.ASSIGNED -> R.string.education_assigned_tab
    EducationViewModel.Tab.COMPLETED -> R.string.education_completed_tab
    EducationViewModel.Tab.QUESTIONS -> R.string.education_questions_tab
}

// ── Lists ────────────────────────────────────────────────────────────────────

@Composable
private fun ItemList(
    items: List<EducationItemDto>,
    warningSigns: List<EducationItemDto>,
    emptyTitle: Int,
    emptyDesc: Int?,
    viewModel: EducationViewModel
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Default.MenuBook, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(emptyTitle), style = MaterialTheme.typography.bodyLarge)
                emptyDesc?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (warningSigns.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.education_warning_title), fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(stringResource(R.string.education_warning_desc), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }
        // Safety content first, as on the web.
        items(warningSigns + items.filter { it.isWarningSignContent != true }, key = { it.resourceId }) { item ->
            ItemCard(item, viewModel)
        }
    }
}

@Composable
private fun ItemCard(item: EducationItemDto, viewModel: EducationViewModel) {
    Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.resourceType == "VIDEO") Icons.Default.PlayCircle else Icons.Default.Article,
                        null, tint = if (item.isWarningSignContent == true) ErrorRed else BrandBlue
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(item.title ?: "", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                item.comprehensionStatus?.let { StatusBadge(text = stringResource(statusRes(it)), color = statusColor(it)) }
            }
            item.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item.category?.let { Text(stringResource(categoryRes(it)), style = MaterialTheme.typography.labelMedium) }
                item.estimatedDuration?.let {
                    Text(stringResource(R.string.education_duration, it), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!item.isCompleted) {
                LinearProgressIndicator(progress = { (item.progressPercentage ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.openAsk(item) }) { Text(stringResource(R.string.ask_about_this)) }
                Button(onClick = { viewModel.openReader(item) }, colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)) {
                    Text(stringResource(if (item.isCompleted) R.string.education_review else R.string.education_open))
                }
            }
        }
    }
}

@Composable
private fun QuestionList(state: EducationViewModel.UiState, viewModel: EducationViewModel) {
    when {
        state.questionsLoading && !state.questionsLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BrandBlue)
        }
        state.questionsFailed && !state.questionsLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(stringResource(R.string.questions_load_failed), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = { viewModel.loadQuestions() }) { Text(stringResource(R.string.retry)) }
            }
        }
        state.questions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(stringResource(R.string.education_no_questions), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = { viewModel.openAsk(null) }) { Text(stringResource(R.string.ask_question)) }
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.questions, key = { it.id }) { q -> QuestionCard(q) }
        }
    }
}

@Composable
private fun QuestionCard(q: EducationQuestionDto) {
    val answered = q.isAnswered == true && !q.answerText.isNullOrBlank()
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (answered) Icons.Default.MarkChatRead else Icons.Default.Schedule, null,
                    tint = if (answered) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.your_question), style = MaterialTheme.typography.labelLarge)
                if (q.isUrgent == true) {
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(text = stringResource(R.string.urgent), color = ErrorRed)
                }
            }
            Text(q.questionText ?: "", style = MaterialTheme.typography.bodyMedium)
            q.createdAt?.let {
                Text(formatDateTime(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            if (answered) {
                Text(stringResource(R.string.education_answer), style = MaterialTheme.typography.labelLarge, color = SuccessGreen)
                Text(q.answerText ?: "", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.awaiting_answer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Reader ───────────────────────────────────────────────────────────────────

@Composable
private fun Reader(item: EducationItemDto, state: EducationViewModel.UiState, viewModel: EducationViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var noViewer by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            item.category?.let { Text(stringResource(categoryRes(it)), style = MaterialTheme.typography.labelMedium) }
            item.estimatedDuration?.let {
                Text(stringResource(R.string.education_duration, it), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.comprehensionStatus?.let { StatusBadge(text = stringResource(statusRes(it)), color = statusColor(it)) }
        }
        if (item.isWarningSignContent == true) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(stringResource(R.string.education_warning_desc), modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
        }
        item.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item.textContent?.takeIf { it.isNotBlank() }?.let {
            Card(shape = RoundedCornerShape(12.dp)) {
                Text(it, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        // External material opens in whatever the device has for it.
        fun open(url: String) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                noViewer = true
            }
        }
        item.videoUrl?.takeIf { it.isNotBlank() }?.let { url ->
            OutlinedButton(onClick = { open(url) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayCircle, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.watch_video))
            }
        }
        item.contentUrl?.takeIf { it.isNotBlank() }?.let { url ->
            OutlinedButton(onClick = { open(url) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open_material))
            }
        }
        if (noViewer) {
            Text(stringResource(R.string.no_app_for_link), color = ErrorRed, style = MaterialTheme.typography.bodySmall)
        }
        if (!item.hasContent) {
            Text(stringResource(R.string.education_no_content), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider()
        Text(stringResource(R.string.education_rate_prompt), style = MaterialTheme.typography.labelLarge)
        Row {
            (1..5).forEach { star ->
                IconButton(onClick = { viewModel.rate(item, star) }, enabled = !state.savingProgress) {
                    Icon(
                        if ((item.rating ?: 0) >= star) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(R.string.education_rate_star, star),
                        tint = if ((item.rating ?: 0) >= star) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        OutlinedButton(onClick = { viewModel.openAsk(item) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ask_about_this))
        }
        when {
            !item.isCompleted -> Button(
                onClick = { viewModel.markComplete(item) }, enabled = !state.savingProgress,
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) { Text(stringResource(R.string.mark_as_read)) }
            item.confirmedUnderstanding != true -> Button(
                onClick = { viewModel.confirmUnderstanding(item) }, enabled = !state.savingProgress,
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) { Text(stringResource(R.string.i_understand_this)) }
            else -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Verified, null, tint = SuccessGreen)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.education_understood), color = SuccessGreen, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Ask sheet ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskSheet(state: EducationViewModel.UiState, viewModel: EducationViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var urgent by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(confirmValueChange = { !state.askSubmitting })
    ModalBottomSheet(
        onDismissRequest = { viewModel.closeAsk() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !state.askSubmitting)
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ask_question), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            state.askTarget?.let {
                Text(stringResource(R.string.ask_about, it.title ?: ""), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.question_placeholder)) },
                minLines = 3,
                isError = text.length > EducationViewModel.QUESTION_MAX,
                supportingText = { Text("${text.length}/${EducationViewModel.QUESTION_MAX}") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = urgent, onCheckedChange = { urgent = it })
                Text(stringResource(R.string.mark_urgent))
            }
            state.askError?.let { err ->
                Text(listOfNotNull(stringResource(err.resId), err.detail).joinToString(": "), color = ErrorRed,
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { viewModel.submitQuestion(text, urgent) },
                enabled = !state.askSubmitting && text.trim().length >= EducationViewModel.QUESTION_MIN &&
                    text.length <= EducationViewModel.QUESTION_MAX,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) {
                if (state.askSubmitting) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sending))
                } else {
                    Text(stringResource(R.string.send_question), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Labels ───────────────────────────────────────────────────────────────────

private fun statusRes(status: String): Int = when (status.uppercase()) {
    "IN_PROGRESS" -> R.string.edu_status_in_progress
    "COMPLETED" -> R.string.edu_status_completed
    "CONFIRMED_UNDERSTANDING" -> R.string.edu_status_confirmed
    "NEEDS_CLARIFICATION" -> R.string.edu_status_needs_clarification
    else -> R.string.edu_status_not_started
}

private fun statusColor(status: String): Color = when (status.uppercase()) {
    "COMPLETED", "CONFIRMED_UNDERSTANDING" -> SuccessGreen
    "NEEDS_CLARIFICATION" -> ErrorRed
    else -> BrandBlue
}

private fun categoryRes(category: String): Int = when (category.uppercase()) {
    "PRENATAL_CARE" -> R.string.edu_cat_prenatal_care
    "NUTRITION" -> R.string.edu_cat_nutrition
    "EXERCISE" -> R.string.edu_cat_exercise
    "LABOR_AND_DELIVERY" -> R.string.edu_cat_labor_and_delivery
    "POSTPARTUM_CARE" -> R.string.edu_cat_postpartum_care
    "BREASTFEEDING" -> R.string.edu_cat_breastfeeding
    "NEWBORN_CARE" -> R.string.edu_cat_newborn_care
    "MENTAL_HEALTH" -> R.string.edu_cat_mental_health
    "WARNING_SIGNS" -> R.string.edu_cat_warning_signs
    "BIRTH_PLAN" -> R.string.edu_cat_birth_plan
    "PRENATAL_VITAMINS" -> R.string.edu_cat_prenatal_vitamins
    "MANAGING_DISCOMFORT" -> R.string.edu_cat_managing_discomfort
    "HIGH_RISK_PREGNANCY" -> R.string.edu_cat_high_risk_pregnancy
    "ULTRASOUND_SCANS" -> R.string.edu_cat_ultrasound_scans
    else -> R.string.edu_cat_other
}

/** "2026-09-21T10:15:00" in the device's short style; the raw text when it does not parse. */
private fun formatDateTime(iso: String): String = runCatching {
    LocalDateTime.parse(iso.take(19)).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()))
}.getOrDefault(iso)
