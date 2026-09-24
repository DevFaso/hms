package com.bitnesttechs.hms.patient.features.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MedicationsViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    val medications = MutableStateFlow<List<MedicationDto>>(emptyList())
    val prescriptions = MutableStateFlow<List<PrescriptionDto>>(emptyList())
    val refills = MutableStateFlow<List<RefillDto>>(emptyList())
    val isLoading = MutableStateFlow(true)

    /** True while the last load failed and the lists are therefore stale or empty. */
    val loadFailed = MutableStateFlow(false)

    /** A localized outcome: a string resource plus an optional detail argument. */
    data class Outcome(val resId: Int, val detail: String? = null)
    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome
    fun clearOutcome() { _outcome.value = null }

    init { load() }

    /**
     * Returns the Job: a caller that has to READ the refreshed lists — the
     * refill-refusal branch below — must join it, because
     * `viewModelScope.launch` returns at the first suspension point and the
     * flows still hold the pre-request values.
     */
    fun load(): Job {
        return viewModelScope.launch {
            isLoading.value = true
            try {
                // Each list is replaced only when its OWN fetch produced one.
                // `?: emptyList()` here meant that a refresh which failed — no
                // connectivity, an expired session, or the reload on the
                // refill-failure path — swapped the patient's medications for
                // an empty tab. A successful fetch that returns nothing still
                // empties it, which is the honest case.
                //
                // Retrofit does NOT throw on a non-2xx: a 401 arrives as
                // `isSuccessful == false` with a null body, so the catch below
                // never sees it and an expired session would have looked like
                // an empty medication list. Every call reports its own outcome.
                val m = async { api.getMedications() }
                val p = async { api.getPrescriptions() }
                val r = async { api.getRefills() }
                val mResp = m.await()
                val pResp = p.await()
                val rResp = r.await()
                mResp.body()?.data?.let { medications.value = it }
                pResp.body()?.data?.let { prescriptions.value = it }
                rResp.body()?.data?.content?.let { refills.value = it }
                reportLoadOutcome(mResp.isSuccessful && pResp.isSuccessful && rResp.isSuccessful)
            } catch (_: Exception) {
                reportLoadOutcome(false)
            }
            finally { isLoading.value = false }
        }
    }

    /**
     * Keeping the previous lists removed the only signal a refresh had failed
     * — the screen used to empty. Say so instead, so an expired session is not
     * a silent no-op. On a COLD open there is nothing stale to show, so the
     * empty states offer a retry rather than claiming old data is on screen.
     */
    private fun reportLoadOutcome(succeeded: Boolean) {
        loadFailed.value = !succeeded
        if (!succeeded && (medications.value.isNotEmpty() || prescriptions.value.isNotEmpty())) {
            _outcome.value = Outcome(R.string.refresh_failed)
        }
    }

    /** Withdraws a refill request the provider has not acted on yet. */
    fun cancelRefill(refillId: String) {
        viewModelScope.launch {
            try {
                val resp = api.cancelRefill(refillId)
                if (resp.isSuccessful) {
                    // Join before announcing, as requestRefill does: `load()`'s
                    // failure path sets an outcome too, and an unjoined reload
                    // that fails a second later would replace "Refill cancelled"
                    // with "Could not refresh" — so the patient would never
                    // learn the cancellation went through.
                    load().join()
                    _outcome.value = Outcome(R.string.refill_cancelled)
                } else {
                    _outcome.value = Outcome(R.string.refill_cancel_failed, "HTTP ${resp.code()}")
                }
            } catch (e: Exception) {
                _outcome.value = Outcome(R.string.refill_cancel_failed, e.message)
            }
        }
    }

    fun requestRefill(prescriptionId: String, pharmacy: String?, notes: String?) {
        viewModelScope.launch {
            try {
                val resp = api.requestRefill(
                    RefillRequest(
                        prescriptionId = prescriptionId,
                        preferredPharmacy = pharmacy,
                        notes = notes
                    )
                )
                if (resp.isSuccessful) {
                    // Join before announcing: `load()`'s own failure path sets an
                    // outcome too, and an unjoined reload that fails a second
                    // later replaced "Refill requested" with "Could not refresh"
                    // — so the patient never learned the request went through.
                    load().join()
                    _outcome.value = Outcome(R.string.refill_requested)
                } else {
                    val detail = serverMessage(resp.errorBody()?.string())
                    // Reload and WAIT for it: the usual refusal is "you already
                    // have one open", and the button only renders while the
                    // loaded list shows none — so the decision below has to be
                    // made against the refreshed list, not the one that let the
                    // button render. Without the join this branch could never
                    // be taken, and the server's English sentence went straight
                    // into a French snackbar.
                    load().join()
                    // requestMedicationRefill checks isRefillable() BEFORE the
                    // one-open-request guard, so a prescription discontinued
                    // since the screen loaded is refused for that reason even
                    // when an open refill also exists. Read the refreshed
                    // prescription first, or we would tell the patient their
                    // dead prescription is merely under review.
                    val refreshed = prescriptions.value.firstOrNull { it.id == prescriptionId }
                    val stillRefillable = refreshed?.statusEnum?.isRefillable ?: true
                    // Here — unlike the button, where a stale "open" row would
                    // wrongly BLOCK the patient — the server has already refused,
                    // so an open row from either source is the better guess than
                    // an English sentence. It also covers what
                    // `refillRequestOpen` cannot see: `latestRefillsFor` grades
                    // only the NEWEST request, so an older PAUSED one that the
                    // server's `findFirst…StatusIn` still counts reads as false.
                    val openRefill = medications.value.firstOrNull { it.id == prescriptionId }
                        ?.openRefillStatus
                        ?: refills.value.firstOrNull {
                            it.prescriptionId == prescriptionId && it.statusEnum.isOpen
                        }?.statusEnum
                    _outcome.value = when {
                        !stillRefillable -> Outcome(R.string.refill_not_refillable)
                        openRefill != null -> Outcome(openRefillMessage(openRefill))
                        // Anything else: the server's own words are still
                        // better than nothing, even untranslated.
                        else -> Outcome(R.string.refill_request_failed, detail ?: "HTTP ${resp.code()}")
                    }
                }
            } catch (e: Exception) {
                _outcome.value = Outcome(R.string.refill_request_failed, e.message)
            }
        }
    }

    /** The error body's message, which is where BusinessException lands. */
    private fun serverMessage(body: String?): String? = body
        ?.let { runCatching { org.json.JSONObject(it).optString("message") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
}
