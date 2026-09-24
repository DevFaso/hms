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
                val m = async { api.getMedications().body()?.data ?: emptyList() }
                val p = async { api.getPrescriptions().body()?.data ?: emptyList() }
                val r = async { api.getRefills().body()?.data?.content ?: emptyList() }
                medications.value = m.await()
                prescriptions.value = p.await()
                refills.value = r.await()
            } catch (_: Exception) {}
            finally { isLoading.value = false }
        }
    }

    /** Withdraws a refill request the provider has not acted on yet. */
    fun cancelRefill(refillId: String) {
        viewModelScope.launch {
            try {
                val resp = api.cancelRefill(refillId)
                if (resp.isSuccessful) {
                    _outcome.value = Outcome(R.string.refill_cancelled)
                    load()
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
                    _outcome.value = Outcome(R.string.refill_requested)
                    load()
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
                    val hasOpen = medications.value.firstOrNull { it.id == prescriptionId }
                        ?.refillRequestOpen
                        ?: refills.value.any { it.prescriptionId == prescriptionId && it.statusEnum.isOpen }
                    _outcome.value = when {
                        !stillRefillable -> Outcome(R.string.refill_not_refillable)
                        hasOpen -> Outcome(R.string.refill_already_open)
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
