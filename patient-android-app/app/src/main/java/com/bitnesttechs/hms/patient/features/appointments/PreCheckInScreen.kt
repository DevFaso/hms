package com.bitnesttechs.hms.patient.features.appointments

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.AppointmentDto
import com.bitnesttechs.hms.patient.core.models.QuestionnaireQuestion
import com.bitnesttechs.hms.patient.features.appointments.PreCheckInViewModel.AnswerProblem
import com.bitnesttechs.hms.patient.features.appointments.PreCheckInViewModel.Demographics
import com.bitnesttechs.hms.patient.features.appointments.PreCheckInViewModel.Step
import com.bitnesttechs.hms.patient.features.appointments.PreCheckInViewModel.UiState
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed

/**
 * The web's pre-check-in form: demographics -> questionnaires -> review and
 * consent. Reached from the appointment detail; on success the caller lands
 * the patient back on the list, which is reloaded so the visit shows as
 * checked in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreCheckInScreen(
    appointment: AppointmentDto,
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    viewModel: PreCheckInViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(appointment.id) { viewModel.load(appointment.id) }
    LaunchedEffect(state.submitted) { if (state.submitted) onCompleted() }
    // Leaving mid-submit would strand the answer: this view model is its only
    // observer, so the list would never reload and the detail would still offer
    // the button. Back waits for the answer.
    BackHandler(enabled = state.isSubmitting) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pre_checkin)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.isSubmitting) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue, titleContentColor = Color.White)
            )
        },
        bottomBar = { StepBar(state, viewModel, appointment.id) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppointmentSummary(appointment)
            StepIndicator(state.step)
            when (state.step) {
                Step.DEMOGRAPHICS -> DemographicsStep(state.demographics, viewModel::updateDemographics)
                Step.QUESTIONNAIRES -> QuestionnairesStep(state, viewModel, appointment.id)
                Step.REVIEW -> ReviewStep(state, viewModel)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AppointmentSummary(appointment: AppointmentDto) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                appointment.staffName ?: stringResource(R.string.unknown_doctor),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold
            )
            Text(
                "${appointment.appointmentDate} ${appointment.timeDisplay ?: ""}".trim(),
                style = MaterialTheme.typography.bodySmall
            )
            appointment.hospitalName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepIndicator(step: Step) {
    val steps = Step.entries
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.step_indicator, steps.indexOf(step) + 1, steps.size),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEach { s ->
                val active = s == step
                Text(
                    stringResource(s.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) BrandBlue else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val Step.titleRes: Int get() = when (this) {
    Step.DEMOGRAPHICS -> R.string.step_demographics
    Step.QUESTIONNAIRES -> R.string.step_questionnaires
    Step.REVIEW -> R.string.step_review
}

// ── Step 1: demographics ─────────────────────────────────────────────────────

@Composable
private fun DemographicsStep(d: Demographics, update: ((Demographics) -> Demographics) -> Unit) {
    Text(stringResource(R.string.demographics_hint), style = MaterialTheme.typography.bodyMedium)

    SectionTitle(stringResource(R.string.contact_info))
    Field(stringResource(R.string.phone), d.phoneNumber, KeyboardType.Phone) { v -> update { it.copy(phoneNumber = v) } }
    Field(stringResource(R.string.email), d.email, KeyboardType.Email) { v -> update { it.copy(email = v) } }
    Field(stringResource(R.string.address), d.addressLine1) { v -> update { it.copy(addressLine1 = v) } }
    Field(stringResource(R.string.city), d.city) { v -> update { it.copy(city = v) } }
    Field(stringResource(R.string.state_region), d.state) { v -> update { it.copy(state = v) } }
    Field(stringResource(R.string.zip_code), d.zipCode) { v -> update { it.copy(zipCode = v) } }

    SectionTitle(stringResource(R.string.emergency_contact))
    Field(stringResource(R.string.name), d.emergencyContactName) { v -> update { it.copy(emergencyContactName = v) } }
    Field(stringResource(R.string.phone), d.emergencyContactPhone, KeyboardType.Phone) { v -> update { it.copy(emergencyContactPhone = v) } }
    Field(stringResource(R.string.relationship), d.emergencyContactRelationship) { v -> update { it.copy(emergencyContactRelationship = v) } }

    SectionTitle(stringResource(R.string.insurance))
    Field(stringResource(R.string.insurance_provider), d.insuranceProvider) { v -> update { it.copy(insuranceProvider = v) } }
    Field(stringResource(R.string.member_id), d.insuranceMemberId) { v -> update { it.copy(insuranceMemberId = v) } }
    Field(stringResource(R.string.insurance_plan), d.insurancePlan) { v -> update { it.copy(insurancePlan = v) } }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth()
    )
}

// ── Step 2: questionnaires ───────────────────────────────────────────────────

@Composable
private fun QuestionnairesStep(state: UiState, viewModel: PreCheckInViewModel, appointmentId: String) {
    when {
        state.loadFailed -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.questionnaires_load_failed), color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = { viewModel.retryLoad(appointmentId) }) { Text(stringResource(R.string.retry)) }
            }
        }
        !state.questionnairesLoaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
        state.questionnaires.isEmpty() -> Text(stringResource(R.string.no_questionnaires), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> state.questionnaires.forEach { q ->
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(q.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    q.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    q.questions.forEach { question ->
                        val value = state.answers[q.id]?.get(question.id)
                        QuestionField(
                            question = question,
                            value = value,
                            problem = viewModel.problem(question, value),
                            onAnswer = { viewModel.answer(q.id, question.id, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionField(question: QuestionnaireQuestion, value: Any?, problem: AnswerProblem?, onAnswer: (Any?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (question.required) "${question.text} *" else question.text,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
        )
        when {
            question.type == "YES_NO" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = value == true, onClick = { onAnswer(true) }, label = { Text(stringResource(R.string.yes)) })
                FilterChip(selected = value == false, onClick = { onAnswer(false) }, label = { Text(stringResource(R.string.no)) })
            }
            question.type == "MULTI_CHOICE" && question.options.isNotEmpty() -> Column {
                question.options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == option, role = Role.RadioButton, onClick = { onAnswer(option) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == option, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(option, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            question.type == "NUMBER" || question.type == "SCALE" -> {
                val range = if (question.min != null || question.max != null)
                    stringResource(R.string.answer_range_hint, fmt(question.min), fmt(question.max)) else null
                OutlinedTextField(
                    value = value as? String ?: "",
                    onValueChange = { onAnswer(it.ifBlank { null }) },
                    singleLine = true,
                    isError = problem != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        when (problem) {
                            AnswerProblem.NOT_A_NUMBER -> Text(stringResource(R.string.answer_not_a_number))
                            AnswerProblem.OUT_OF_RANGE -> Text(stringResource(R.string.answer_out_of_range, fmt(question.min), fmt(question.max)))
                            null -> range?.let { Text(it) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> OutlinedTextField(
                value = value as? String ?: "",
                onValueChange = { onAnswer(it.ifBlank { null }) },
                minLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** "3" for 3.0, "2.5" for 2.5, "–" when the bound is absent. */
private fun fmt(d: Double?): String = when {
    d == null -> "\u2013"
    d % 1.0 == 0.0 -> d.toLong().toString()
    else -> d.toString()
}

// ── Step 3: review and consent ───────────────────────────────────────────────

@Composable
private fun ReviewStep(state: UiState, viewModel: PreCheckInViewModel) {
    val d = state.demographics
    val updated = listOf(
        R.string.phone to d.phoneNumber, R.string.email to d.email, R.string.address to d.addressLine1,
        R.string.city to d.city, R.string.state_region to d.state, R.string.zip_code to d.zipCode,
        R.string.emergency_contact to d.emergencyContactName, R.string.phone to d.emergencyContactPhone,
        R.string.relationship to d.emergencyContactRelationship,
        R.string.insurance_provider to d.insuranceProvider, R.string.member_id to d.insuranceMemberId,
        R.string.insurance_plan to d.insurancePlan
    ).filter { it.second.isNotBlank() }

    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.step_demographics), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (updated.isEmpty()) {
                Text(stringResource(R.string.nothing_to_update), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.updated_fields, updated.size), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                updated.forEach { (labelRes, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.step_questionnaires), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (state.questionnaires.isEmpty()) stringResource(R.string.no_questionnaires)
                else stringResource(R.string.questionnaires_completed, viewModel.answeredQuestionnaires(state), state.questionnaires.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = state.consentAcknowledged, role = Role.Checkbox,
                        onClick = { viewModel.setConsent(!state.consentAcknowledged) }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = state.consentAcknowledged, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.consent_text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    state.submitError?.let { detail ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                listOfNotNull(stringResource(R.string.precheckin_failed), detail).joinToString(": "),
                modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// ── Bottom bar: back / next / submit ─────────────────────────────────────────

@Composable
private fun StepBar(state: UiState, viewModel: PreCheckInViewModel, appointmentId: String) {
    val steps = Step.entries
    val index = steps.indexOf(state.step)
    val questionnairesOk = state.step != Step.QUESTIONNAIRES ||
        (state.questionnairesLoaded && viewModel.questionnairesComplete(state))
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.step == Step.QUESTIONNAIRES && state.questionnairesLoaded && !questionnairesOk) {
                // Say which: a starred question left blank, or an answer that cannot be sent as typed.
                val hint = if (viewModel.missingRequired(state)) R.string.required_answers_missing else R.string.answers_invalid
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = ErrorRed)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    TextButton(onClick = { viewModel.goTo(steps[index - 1]) }, enabled = !state.isSubmitting) {
                        Text(stringResource(R.string.back))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (state.step == Step.REVIEW) {
                    Button(
                        onClick = { viewModel.submit(appointmentId) },
                        enabled = state.consentAcknowledged && !state.isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.submitting))
                        } else {
                            Text(stringResource(R.string.submit_pre_checkin))
                        }
                    }
                } else {
                    Button(
                        onClick = { viewModel.goTo(steps[index + 1]) },
                        enabled = questionnairesOk,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                    ) { Text(stringResource(R.string.next)) }
                }
            }
        }
    }
}
