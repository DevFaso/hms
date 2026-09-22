package com.bitnesttechs.hms.patient.features.screenings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.di.ApplicationScope
import com.bitnesttechs.hms.patient.core.models.ProInstrumentView
import com.bitnesttechs.hms.patient.core.models.ProResponseCreate
import com.bitnesttechs.hms.patient.core.models.ProScreeningAvailable
import com.bitnesttechs.hms.patient.core.models.ProScreeningEntry
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * A mother answers her own mental-health screening (EPDS) from home while a
 * postpartum plan is open for her, the web's My Screenings. Deliberately
 * score-free: the backend never sends a total to this surface and this
 * screen never computes one. What she sees afterwards is whether her care
 * team will follow up, and whether they were alerted straight away.
 */
@HiltViewModel
class ScreeningsViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {

    data class Outcome(@StringRes val resId: Int, val detail: String? = null)

    data class UiState(
        val loading: Boolean = true,
        /** Kept apart from "nothing open": an empty list must never stand in for a failed request. */
        val failed: Boolean = false,
        val available: List<ProScreeningAvailable> = emptyList(),
        val history: List<ProScreeningEntry> = emptyList(),
        val active: ProScreeningAvailable? = null,
        val instrument: ProInstrumentView? = null,
        val instrumentLoading: Boolean = false,
        val instrumentFailed: Boolean = false,
        val language: String = "",
        /** itemNo -> optionNo */
        val answers: Map<Int, Int> = emptyMap(),
        val submitting: Boolean = false,
        val submitError: Outcome? = null,
        val outcome: Outcome? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var instrumentJob: Job? = null

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val resp = api.getMyScreenings()
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    _state.update { it.copy(loading = false, available = body.available, history = body.history) }
                } else {
                    _state.update { it.copy(loading = false, failed = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    /** Opens the form in the device language; the server falls back to English and says which it served. */
    fun start(instrument: ProScreeningAvailable) {
        val language = Locale.getDefault().language
        _state.update { it.copy(active = instrument, answers = emptyMap(), submitError = null, language = language) }
        loadInstrument(instrument.code, language)
    }

    fun cancel() {
        instrumentJob?.cancel()
        _state.update { it.copy(active = null, instrument = null, instrumentLoading = false, instrumentFailed = false, answers = emptyMap(), submitError = null) }
    }

    fun changeLanguage(language: String) {
        val active = _state.value.active ?: return
        _state.update { it.copy(language = language) }
        loadInstrument(active.code, language)
    }

    fun retryInstrument() {
        val s = _state.value
        val active = s.active ?: return
        loadInstrument(active.code, s.language)
    }

    /**
     * One request at a time, keyed on (code, language): a language switch
     * cancels the one before it, so a slow first answer can never overwrite
     * the wording the mother chose.
     */
    private fun loadInstrument(code: String, language: String) {
        instrumentJob?.cancel()
        _state.update { it.copy(instrumentLoading = true, instrumentFailed = false) }
        instrumentJob = viewModelScope.launch {
            try {
                val resp = api.getScreeningInstrument(code, language.ifBlank { null })
                val view = resp.body()
                if (resp.isSuccessful && view != null) {
                    // Item and option numbers are language-independent, so a
                    // language switch keeps what was already answered (as the web
                    // does); only answers to items the new wording lacks are dropped.
                    val itemNos = view.items.map { it.itemNo }.toSet()
                    _state.update {
                        it.copy(instrument = view, instrumentLoading = false,
                            language = view.language ?: it.language,
                            answers = it.answers.filterKeys { k -> k in itemNos })
                    }
                } else {
                    _state.update { it.copy(instrument = null, instrumentLoading = false, instrumentFailed = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(instrument = null, instrumentLoading = false, instrumentFailed = true) }
            }
        }
    }

    fun answer(itemNo: Int, optionNo: Int) {
        _state.update { s ->
            val next = s.copy(answers = s.answers + (itemNo to optionNo))
            // An "items missing" refusal follows the remaining gaps; any other error clears.
            val error = s.submitError
            val still = if (error?.resId == R.string.screening_incomplete) unanswered(next) else emptyList()
            next.copy(submitError = if (still.isEmpty()) null else Outcome(R.string.screening_incomplete, still.joinToString(", ")))
        }
    }

    /** Item numbers still without an answer, in order. */
    fun unanswered(s: UiState): List<Int> =
        s.instrument?.items?.map { it.itemNo }?.filter { it !in s.answers } ?: emptyList()

    /**
     * Runs in [applicationScope]: a back press mid-flight must not abandon an
     * answer the server may already have recorded (a safety-item answer
     * alerts the care team on receipt).
     */
    fun submit() {
        val s = _state.value
        val instrument = s.instrument ?: return
        if (s.submitting) return
        val missing = unanswered(s)
        if (missing.isNotEmpty()) {
            _state.update { it.copy(submitError = Outcome(R.string.screening_incomplete, missing.joinToString(", "))) }
            return
        }
        val request = ProResponseCreate(
            instrumentCode = instrument.code,
            language = instrument.language,
            answers = s.answers.mapKeys { it.key.toString() }
        )
        _state.update { it.copy(submitting = true, submitError = null) }
        applicationScope.launch {
            try {
                val resp = api.submitScreening(request)
                val entry = resp.body()
                if (resp.isSuccessful && entry != null) {
                    instrumentJob?.cancel()
                    _state.update {
                        it.copy(
                            submitting = false, active = null, instrument = null, answers = emptyMap(),
                            history = listOf(entry) + it.history,
                            outcome = Outcome(R.string.screening_submitted)
                        )
                    }
                } else {
                    val detail = resp.errorBody()?.string()
                        ?.let { runCatching { org.json.JSONObject(it).optString("message") }.getOrNull() }
                        ?.takeIf { it.isNotBlank() }
                    _state.update { it.copy(submitting = false, submitError = Outcome(R.string.screening_submit_failed, detail)) }
                    // A refusal usually means the plan closed since the form was
                    // opened; the overview is reloaded so the list stops offering it.
                    if (resp.code() in 400..499) load()
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, submitError = Outcome(R.string.screening_submit_failed, e.message)) }
            }
        }
    }

    fun clearOutcome() { _state.update { it.copy(outcome = null) } }
}
