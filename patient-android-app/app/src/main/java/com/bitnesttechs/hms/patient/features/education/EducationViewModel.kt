package com.bitnesttechs.hms.patient.features.education

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.di.ApplicationScope
import com.bitnesttechs.hms.patient.core.models.EducationItemDto
import com.bitnesttechs.hms.patient.core.models.EducationProgressUpdate
import com.bitnesttechs.hms.patient.core.models.EducationQuestionDto
import com.bitnesttechs.hms.patient.core.models.EducationQuestionSubmit
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The web's My Education: what the care team assigned (warning-sign material
 * first), the reader with progress, rating and "I understand this", and the
 * questions the patient asked with the team's answers.
 */
@HiltViewModel
class EducationViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {

    enum class Tab { ASSIGNED, COMPLETED, QUESTIONS }

    data class Outcome(@StringRes val resId: Int, val detail: String? = null)

    data class UiState(
        val loading: Boolean = true,
        val failed: Boolean = false,
        val items: List<EducationItemDto> = emptyList(),
        val tab: Tab = Tab.ASSIGNED,
        val questions: List<EducationQuestionDto> = emptyList(),
        val questionsLoaded: Boolean = false,
        val questionsLoading: Boolean = false,
        val questionsFailed: Boolean = false,
        /** The item open in the reader. */
        val reading: EducationItemDto? = null,
        val savingProgress: Boolean = false,
        /** The item a question is being asked about; null with askOpen = a general question. */
        val askTarget: EducationItemDto? = null,
        val askOpen: Boolean = false,
        val askSubmitting: Boolean = false,
        val askError: Outcome? = null,
        val outcome: Outcome? = null
    ) {
        val assigned: List<EducationItemDto> get() = items.filter { !it.isCompleted }
        val completed: List<EducationItemDto> get() = items.filter { it.isCompleted }
        /** Warning-sign material is safety content; it is surfaced first. */
        val warningSigns: List<EducationItemDto> get() = assigned.filter { it.isWarningSignContent == true }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val resp = api.getMyEducation()
                val list = resp.body()?.data
                if (resp.isSuccessful && list != null) {
                    _state.update { it.copy(loading = false, items = list) }
                } else {
                    _state.update { it.copy(loading = false, failed = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun selectTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        if (tab == Tab.QUESTIONS && !_state.value.questionsLoaded) loadQuestions()
    }

    fun loadQuestions() {
        viewModelScope.launch {
            _state.update { it.copy(questionsLoading = true, questionsFailed = false) }
            try {
                val resp = api.getEducationQuestions()
                val list = resp.body()?.data
                if (resp.isSuccessful && list != null) {
                    _state.update { it.copy(questions = list, questionsLoaded = true, questionsLoading = false) }
                } else {
                    _state.update { it.copy(questionsLoading = false, questionsFailed = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(questionsLoading = false, questionsFailed = true) }
            }
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /** Opening counts as starting, so staff can see engagement; a finished resource is never downgraded. */
    fun openReader(item: EducationItemDto) {
        _state.update { it.copy(reading = item) }
        if (!item.isCompleted && (item.progressPercentage ?: 0) == 0) {
            saveProgress(item, EducationProgressUpdate(progressPercentage = 1), notify = false)
        }
    }

    fun closeReader() {
        _state.update { it.copy(reading = null) }
    }

    fun markComplete(item: EducationItemDto) =
        saveProgress(item, EducationProgressUpdate(progressPercentage = 100), notify = true)

    fun confirmUnderstanding(item: EducationItemDto) =
        saveProgress(item, EducationProgressUpdate(progressPercentage = 100, confirmedUnderstanding = true), notify = true)

    fun rate(item: EducationItemDto, rating: Int) =
        saveProgress(item, EducationProgressUpdate(rating = rating.coerceIn(1, 5)), notify = true)

    /**
     * Runs in [applicationScope]: closing the reader or the screen mid-flight
     * must not abandon a "read" or "understood" the server may already hold.
     */
    private fun saveProgress(item: EducationItemDto, update: EducationProgressUpdate, notify: Boolean) {
        if (_state.value.savingProgress) return
        _state.update { it.copy(savingProgress = true) }
        applicationScope.launch {
            try {
                val resp = api.updateEducationProgress(item.resourceId, update)
                val updated = resp.body()?.data
                if (resp.isSuccessful && updated != null) {
                    _state.update { s ->
                        s.copy(
                            savingProgress = false,
                            items = s.items.map { if (it.resourceId == updated.resourceId) updated else it },
                            reading = if (s.reading?.resourceId == updated.resourceId) updated else s.reading,
                            outcome = if (notify) Outcome(R.string.education_saved) else s.outcome
                        )
                    }
                } else {
                    _state.update {
                        it.copy(savingProgress = false,
                            outcome = if (notify) Outcome(R.string.education_save_failed, serverMessage(resp.errorBody()?.string())) else it.outcome)
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(savingProgress = false, outcome = if (notify) Outcome(R.string.education_save_failed, e.message) else it.outcome)
                }
            }
        }
    }

    // ── Questions ────────────────────────────────────────────────────────────

    fun openAsk(item: EducationItemDto?) {
        _state.update { it.copy(askTarget = item, askOpen = true, askError = null) }
    }

    fun closeAsk() {
        if (_state.value.askSubmitting) return
        _state.update { it.copy(askTarget = null, askOpen = false, askError = null) }
    }

    fun submitQuestion(text: String, urgent: Boolean) {
        val trimmed = text.trim()
        val s = _state.value
        if (s.askSubmitting) return
        if (trimmed.length < QUESTION_MIN) {
            _state.update { it.copy(askError = Outcome(R.string.question_too_short)) }
            return
        }
        if (trimmed.length > QUESTION_MAX) {
            _state.update { it.copy(askError = Outcome(R.string.question_too_long)) }
            return
        }
        _state.update { it.copy(askSubmitting = true, askError = null) }
        applicationScope.launch {
            try {
                val resp = api.submitEducationQuestion(
                    EducationQuestionSubmit(resourceId = s.askTarget?.resourceId, questionText = trimmed, isUrgent = urgent)
                )
                val question = resp.body()?.data
                if (resp.isSuccessful && question != null) {
                    _state.update {
                        it.copy(
                            askSubmitting = false, askOpen = false, askTarget = null,
                            questions = listOf(question) + it.questions, questionsLoaded = true,
                            outcome = Outcome(R.string.question_sent)
                        )
                    }
                } else {
                    _state.update {
                        it.copy(askSubmitting = false, askError = Outcome(R.string.question_failed, serverMessage(resp.errorBody()?.string())))
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(askSubmitting = false, askError = Outcome(R.string.question_failed, e.message)) }
            }
        }
    }

    fun clearOutcome() { _state.update { it.copy(outcome = null) } }

    private fun serverMessage(body: String?): String? = body
        ?.let { runCatching { org.json.JSONObject(it).optString("message") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    companion object {
        const val QUESTION_MIN = 5
        const val QUESTION_MAX = 2000
    }
}
