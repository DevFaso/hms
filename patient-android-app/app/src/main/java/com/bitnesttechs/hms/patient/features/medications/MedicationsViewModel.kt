package com.bitnesttechs.hms.patient.features.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
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

    fun load() {
        viewModelScope.launch {
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
                    // The server explains WHY it refused — "You already have a
                    // refill request awaiting review for this medication", or
                    // that the provider put it on hold. Throwing that away for a
                    // flat "Failed to request refill" leaves the patient with no
                    // idea what to do next.
                    _outcome.value = Outcome(
                        R.string.refill_request_failed,
                        serverMessage(resp.errorBody()?.string()) ?: "HTTP ${resp.code()}"
                    )
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
