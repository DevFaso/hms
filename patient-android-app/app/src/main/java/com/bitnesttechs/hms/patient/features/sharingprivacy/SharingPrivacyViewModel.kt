package com.bitnesttechs.hms.patient.features.sharingprivacy

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.di.ApplicationScope
import com.bitnesttechs.hms.patient.core.models.DisclosureAccountingDto
import com.bitnesttechs.hms.patient.core.models.DisclosureEntryDto
import com.bitnesttechs.hms.patient.core.models.OptOutRequest
import com.bitnesttechs.hms.patient.core.models.RecordSharingOptOutDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.Response
import javax.inject.Inject

/**
 * The web's "Who accessed my record" (my-sharing, E9 #66).
 *
 * The consent-grant list that used to live here is gone, as on the web: a
 * patient's record follows them on the treatment relationship, so there is
 * nothing to grant. What a patient controls is the opt-out (close the door
 * to other hospitals, keep their own hospital's access) and what they see is
 * every access and disclosure, emergency ones counted first.
 *
 * The two halves load independently: a failed opt-out read must never be
 * shown as "sharing is on", and a failed access-log read must never be shown
 * as "nobody looked at your record".
 */
@HiltViewModel
class SharingPrivacyViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val saveTracker: OptOutSaveTracker
) : ViewModel() {

    data class Outcome(@StringRes val resId: Int, val detail: String? = null)

    data class UiState(
        /* ── Opt-out ── */
        val optOutLoading: Boolean = true,
        /** Load failure is shown as such, never as "sharing is on". */
        val optOutFailed: Boolean = false,
        val optOut: RecordSharingOptOutDto? = null,
        val optOutSaving: Boolean = false,
        val showOptOutForm: Boolean = false,
        val optOutReason: String = "",
        /* ── Access log ── */
        val logLoading: Boolean = true,
        /** Kept apart from "no rows": an empty list must never stand in for a failed request. */
        val logFailed: Boolean = false,
        val accounting: DisclosureAccountingDto? = null,
        val entries: List<DisclosureEntryDto> = emptyList(),
        val page: Int = 0,
        val totalPages: Int = 0,
        val loadingMore: Boolean = false,
        val loadMoreFailed: Boolean = false,
        val outcome: Outcome? = null
    ) {
        val optedOut: Boolean get() = optOut?.inForce == true
        val hasMore: Boolean get() = page + 1 < totalPages
        val emergencyCount: Long get() = accounting?.countsByCategory?.get(CATEGORY_EMERGENCY) ?: 0
        val externalCount: Long get() = accounting?.externalDisclosures ?: 0
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The opt-out endpoint is patient-scoped; the id comes from the profile, as on the web. */
    private var patientId: String = ""

    init {
        loadAccessLog()
        loadOptOut()
    }

    /* ── Access log ── */

    fun loadAccessLog() {
        viewModelScope.launch {
            _state.update { it.copy(logLoading = true, logFailed = false, loadMoreFailed = false) }
            try {
                val resp = api.getMyDisclosures(page = 0, size = PAGE_SIZE)
                val body = resp.body()?.data
                if (resp.isSuccessful && body != null) {
                    _state.update {
                        it.copy(
                            logLoading = false, accounting = body, entries = body.entries,
                            page = body.page, totalPages = body.totalPages
                        )
                    }
                } else {
                    _state.update { it.copy(logLoading = false, logFailed = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(logLoading = false, logFailed = true) }
            }
        }
    }

    /** The next page of events; the counts above the list already cover the whole history. */
    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || s.logLoading || !s.hasMore) return
        val next = s.page + 1
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, loadMoreFailed = false) }
            try {
                val resp = api.getMyDisclosures(page = next, size = PAGE_SIZE)
                val body = resp.body()?.data
                if (resp.isSuccessful && body != null) {
                    _state.update { cur ->
                        val seen = cur.entries.mapTo(HashSet()) { it.id }
                        cur.copy(
                            loadingMore = false, accounting = body,
                            entries = cur.entries + body.entries.filter { it.id !in seen },
                            page = body.page, totalPages = body.totalPages
                        )
                    }
                } else {
                    _state.update { it.copy(loadingMore = false, loadMoreFailed = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, loadMoreFailed = true) }
            }
        }
    }

    /* ── Opt-out ── */

    fun loadOptOut() {
        viewModelScope.launch {
            _state.update { it.copy(optOutLoading = true, optOutFailed = false) }
            try {
                // A save started by a ViewModel that is gone (back, then reopen)
                // must land before this read, or the card shows the old state.
                saveTracker.inFlight.value?.join()
                if (patientId.isBlank()) {
                    val profile = api.getProfile()
                    patientId = profile.body()?.data?.id.orEmpty()
                }
                if (patientId.isBlank()) {
                    _state.update { it.copy(optOutLoading = false, optOutFailed = true) }
                    return@launch
                }
                val resp = api.getRecordSharingOptOut(patientId)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    _state.update { it.copy(optOutLoading = false, optOut = body) }
                } else {
                    _state.update { it.copy(optOutLoading = false, optOutFailed = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(optOutLoading = false, optOutFailed = true) }
            }
        }
    }

    fun openOptOutForm() {
        _state.update { it.copy(showOptOutForm = true, optOutReason = "") }
    }

    fun cancelOptOutForm() {
        if (_state.value.optOutSaving) return
        _state.update { it.copy(showOptOutForm = false) }
    }

    /** Mirrors the web textarea's maxlength, which is the server's `@Size(max = 1000)`. */
    fun updateOptOutReason(value: String) {
        _state.update { it.copy(optOutReason = value.take(REASON_MAX)) }
    }

    /**
     * Runs in [applicationScope]: closing the sheet or leaving the screen must
     * not abandon a request the server may already have honoured. One at a
     * time: a second opt-out is a 409 on the server anyway.
     */
    fun confirmOptOut() {
        val s = _state.value
        if (s.optOutSaving || patientId.isBlank()) return
        val reason = s.optOutReason.trim().takeIf { it.isNotEmpty() }
        save(R.string.sharing_opt_out_saved_on, R.string.sharing_already_off) {
            api.optOutOfRecordSharing(patientId, OptOutRequest(reason))
        }
    }

    /** The web has no second confirmation for re-opening the record; neither does this. */
    fun revokeOptOut() {
        val s = _state.value
        if (s.optOutSaving || patientId.isBlank()) return
        save(R.string.sharing_opt_out_saved_off, R.string.sharing_already_on) { api.revokeRecordSharingOptOut(patientId) }
    }

    /**
     * One save at a time, process-wide, registered with [saveTracker] so a
     * ViewModel created after this one is gone still reads the committed state.
     *
     * A 409 is "already in that state" (the server refuses a second opt-out
     * and a revocation of none): the real state is reloaded, the sheet closes
     * and [alreadyRes] is shown alone, never the server's sentence. Any other
     * refusal shows the server's message after the localized headline; an
     * exception (offline, malformed body) shows the headline only, since its
     * text is a developer's, not a patient's.
     */
    private fun save(
        @StringRes savedRes: Int,
        @StringRes alreadyRes: Int,
        call: suspend () -> Response<RecordSharingOptOutDto>
    ) {
        if (saveTracker.inFlight.value != null) return
        _state.update { it.copy(optOutSaving = true) }
        val job: Job = applicationScope.launch(start = CoroutineStart.LAZY) {
            try {
                val resp = call()
                val body = resp.body()
                when {
                    resp.isSuccessful && body != null -> _state.update {
                        it.copy(optOutSaving = false, showOptOutForm = false, optOut = body, outcome = Outcome(savedRes))
                    }
                    resp.code() == 409 -> {
                        _state.update { it.copy(optOutSaving = false, showOptOutForm = false, outcome = Outcome(alreadyRes)) }
                        loadOptOut()
                    }
                    else -> _state.update {
                        it.copy(optOutSaving = false,
                            outcome = Outcome(R.string.sharing_opt_out_failed, serverMessage(resp.errorBody()?.string())))
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(optOutSaving = false, outcome = Outcome(R.string.sharing_opt_out_failed)) }
            }
        }
        if (saveTracker.start(job)) job.start()
        else {
            job.cancel()
            _state.update { it.copy(optOutSaving = false) }
        }
    }

    fun clearOutcome() { _state.update { it.copy(outcome = null) } }

    /** The wrapper's message, when the body is the usual ApiResponseWrapper. */
    private fun serverMessage(body: String?): String? = body
        ?.let { runCatching { org.json.JSONObject(it).optString("message") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    companion object {
        /** The web asks for one page of 50. */
        const val PAGE_SIZE = 50
        const val REASON_MAX = 1000
        const val CATEGORY_EMERGENCY = "EMERGENCY_ACCESS"
    }
}
