package com.bitnesttechs.hms.patient.features.appointments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.di.ApplicationScope
import com.bitnesttechs.hms.patient.core.models.PreCheckInRequest
import com.bitnesttechs.hms.patient.core.models.QuestionnaireDto
import com.bitnesttechs.hms.patient.core.models.QuestionnaireQuestion
import com.bitnesttechs.hms.patient.core.models.QuestionnaireSubmission
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * The web's three-step pre-check-in: demographics that have changed (blank
 * keeps the current value), the hospital's questionnaires for the visit, then
 * review and consent. Answers are kept as the patient typed them and typed on
 * submit (YES_NO -> boolean, NUMBER -> number, the rest -> text, SCALE
 * included), which is the JSON the web produces.
 */
@HiltViewModel
class PreCheckInViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {

    enum class Step { DEMOGRAPHICS, QUESTIONNAIRES, REVIEW }

    /** Why an answer cannot be submitted as typed. */
    enum class AnswerProblem { NOT_A_NUMBER, OUT_OF_RANGE }

    data class Demographics(
        val phoneNumber: String = "",
        val email: String = "",
        val addressLine1: String = "",
        val city: String = "",
        val state: String = "",
        val zipCode: String = "",
        val emergencyContactName: String = "",
        val emergencyContactPhone: String = "",
        val emergencyContactRelationship: String = "",
        val insuranceProvider: String = "",
        val insuranceMemberId: String = "",
        val insurancePlan: String = ""
    )

    data class ParsedQuestionnaire(
        val id: String,
        val title: String,
        val description: String?,
        val questions: List<QuestionnaireQuestion>
    )

    data class UiState(
        val step: Step = Step.DEMOGRAPHICS,
        val questionnaires: List<ParsedQuestionnaire> = emptyList(),
        val questionnairesLoaded: Boolean = false,
        val loadFailed: Boolean = false,
        /** questionnaireId -> (questionId -> Boolean for YES_NO, String otherwise). */
        val answers: Map<String, Map<String, Any>> = emptyMap(),
        val demographics: Demographics = Demographics(),
        val consentAcknowledged: Boolean = false,
        val isSubmitting: Boolean = false,
        val submitError: String? = null,
        val submitted: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var loadedFor: String? = null

    fun load(appointmentId: String) {
        if (loadedFor == appointmentId && !_state.value.loadFailed) return
        loadedFor = appointmentId
        _state.update { it.copy(questionnairesLoaded = false, loadFailed = false) }
        viewModelScope.launch {
            try {
                val resp = api.getAppointmentQuestionnaires(appointmentId)
                val list = resp.body()?.data
                if (resp.isSuccessful && list != null) {
                    _state.update { it.copy(questionnaires = list.map(::parse), questionnairesLoaded = true) }
                } else {
                    _state.update { it.copy(loadFailed = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadFailed = true) }
            }
        }
    }

    fun retryLoad(appointmentId: String) {
        loadedFor = null
        load(appointmentId)
    }

    /** The web's parse: a questionnaire whose JSON does not parse has no questions. */
    private fun parse(dto: QuestionnaireDto): ParsedQuestionnaire {
        val questions = runCatching {
            val arr = JSONArray(dto.questions ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QuestionnaireQuestion(
                    id = id,
                    text = o.optString("text").ifBlank { id },
                    type = o.optString("type").ifBlank { "TEXT" }.uppercase(),
                    required = o.optBoolean("required", false),
                    options = o.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                        ?.filter { it.isNotBlank() } ?: emptyList(),
                    min = if (o.has("min") && !o.isNull("min")) o.optDouble("min") else null,
                    max = if (o.has("max") && !o.isNull("max")) o.optDouble("max") else null
                )
            }
        }.getOrDefault(emptyList())
        return ParsedQuestionnaire(dto.id, dto.title.orEmpty(), dto.description, questions)
    }

    fun goTo(step: Step) {
        _state.update { it.copy(step = step, submitError = null) }
    }

    fun updateDemographics(change: (Demographics) -> Demographics) {
        _state.update { it.copy(demographics = change(it.demographics)) }
    }

    /** null clears the answer. */
    fun answer(questionnaireId: String, questionId: String, value: Any?) {
        _state.update { s ->
            val current = s.answers[questionnaireId].orEmpty().toMutableMap()
            if (value == null) current.remove(questionId) else current[questionId] = value
            s.copy(answers = s.answers + (questionnaireId to current))
        }
    }

    fun setConsent(acknowledged: Boolean) {
        _state.update { it.copy(consentAcknowledged = acknowledged) }
    }

    fun answerOf(questionnaireId: String, questionId: String): Any? =
        _state.value.answers[questionnaireId]?.get(questionId)

    /** Numbers must parse and sit inside min..max; every other type is free text or a choice. */
    fun problem(question: QuestionnaireQuestion, value: Any?): AnswerProblem? {
        if (!isNumeric(question)) return null
        val text = (value as? String)?.trim().orEmpty()
        if (text.isEmpty()) return null
        // toDoubleOrNull accepts NaN, Infinity and 1e400, which org.json then refuses.
        val number = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return AnswerProblem.NOT_A_NUMBER
        val below = question.min != null && number < question.min
        val above = question.max != null && number > question.max
        return if (below || above) AnswerProblem.OUT_OF_RANGE else null
    }

    private fun isNumeric(question: QuestionnaireQuestion) = question.type == "NUMBER" || question.type == "SCALE"

    private fun isAnswered(value: Any?): Boolean = when (value) {
        is Boolean -> true
        is String -> value.isNotBlank()
        else -> value != null
    }

    /** A required question without an answer. */
    fun missingRequired(s: UiState): Boolean = s.questionnaires.any { q ->
        q.questions.any { it.required && !isAnswered(s.answers[q.id]?.get(it.id)) }
    }

    /** An answer that cannot be sent as typed (a number that is not one, or out of range). */
    fun hasAnswerProblems(s: UiState): Boolean = s.questionnaires.any { q ->
        q.questions.any { problem(it, s.answers[q.id]?.get(it.id)) != null }
    }

    /** Required questions answered and no numeric problems: the questionnaires step may advance. */
    fun questionnairesComplete(s: UiState): Boolean = !missingRequired(s) && !hasAnswerProblems(s)

    /** Questionnaires with at least one answer: what the review step counts and what is sent. */
    fun answeredQuestionnaires(s: UiState): Int =
        s.questionnaires.count { q -> s.answers[q.id].orEmpty().values.any { isAnswered(it) } }

    /**
     * Runs in [applicationScope]: a back press mid-flight must not abandon a
     * request the server may already have honoured.
     */
    fun submit(appointmentId: String) {
        val s = _state.value
        if (s.isSubmitting || !s.consentAcknowledged) return
        val request = buildRequest(appointmentId, s)
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        applicationScope.launch {
            try {
                val resp = api.submitPreCheckIn(appointmentId, request)
                if (resp.isSuccessful) {
                    _state.update { it.copy(isSubmitting = false, submitted = true) }
                } else {
                    _state.update { it.copy(isSubmitting = false, submitError = serverMessage(resp.errorBody()?.string())) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isSubmitting = false, submitError = e.message) }
            }
        }
    }

    private fun buildRequest(appointmentId: String, s: UiState): PreCheckInRequest {
        val d = s.demographics
        fun String.orNull() = trim().ifBlank { null }
        val responses = s.questionnaires.mapNotNull { q ->
            val answers = s.answers[q.id].orEmpty().filterValues { isAnswered(it) }
            if (answers.isEmpty()) return@mapNotNull null
            val json = JSONObject()
            answers.forEach { (questionId, value) ->
                val question = q.questions.find { it.id == questionId }
                json.put(questionId, typed(question, value))
            }
            QuestionnaireSubmission(questionnaireId = q.id, responses = json.toString())
        }
        return PreCheckInRequest(
            appointmentId = appointmentId,
            phoneNumber = d.phoneNumber.orNull(),
            email = d.email.orNull(),
            addressLine1 = d.addressLine1.orNull(),
            city = d.city.orNull(),
            state = d.state.orNull(),
            zipCode = d.zipCode.orNull(),
            emergencyContactName = d.emergencyContactName.orNull(),
            emergencyContactPhone = d.emergencyContactPhone.orNull(),
            emergencyContactRelationship = d.emergencyContactRelationship.orNull(),
            insuranceProvider = d.insuranceProvider.orNull(),
            insuranceMemberId = d.insuranceMemberId.orNull(),
            insurancePlan = d.insurancePlan.orNull(),
            questionnaireResponses = responses,
            consentAcknowledged = s.consentAcknowledged
        )
    }

    /**
     * The JSON value the web would send for this answer: only NUMBER becomes a
     * number; the web's form has no SCALE input and stores it as text.
     */
    private fun typed(question: QuestionnaireQuestion?, value: Any): Any {
        if (value is Boolean) return value
        val text = value.toString().trim()
        if (question != null && question.type == "NUMBER") {
            val number = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return text
            // toLong() saturates past 2^63; such a value stays a Double.
            return if (number % 1.0 == 0.0 && kotlin.math.abs(number) < 9.0e18) number.toLong() else number
        }
        return text
    }

    private fun serverMessage(body: String?): String? = body
        ?.let { runCatching { JSONObject(it).optString("message") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
}
