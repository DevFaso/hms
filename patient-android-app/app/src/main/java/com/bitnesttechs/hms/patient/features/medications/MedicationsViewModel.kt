package com.bitnesttechs.hms.patient.features.medications

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MedicationsViewModel @Inject constructor(private val api: ApiService) : ViewModel() {

    private companion object {
        const val TAG = "MedicationsViewModel"
    }

    val medications = MutableStateFlow<List<MedicationDto>>(emptyList())
    val prescriptions = MutableStateFlow<List<PrescriptionDto>>(emptyList())
    val refills = MutableStateFlow<List<RefillDto>>(emptyList())
    val isLoading = MutableStateFlow(true)

    /**
     * Which of the three fetches failed, per list. One flag for all three
     * would tell the Refills tab "we could not load this" because the
     * PRESCRIPTIONS call 500'd, over a refills list that loaded fine and is
     * legitimately empty.
     */
    data class LoadFailures(
        val medications: Boolean = false,
        val prescriptions: Boolean = false,
        val refills: Boolean = false
    ) {
        val any: Boolean get() = medications || prescriptions || refills
    }

    val loadFailed = MutableStateFlow(LoadFailures())

    /**
     * Prescription id → the open refill on it. Rebuilt at the end of every
     * load rather than scanned per row: `/me/patient/prescriptions` is
     * unpaged, so a long-standing patient's list is unbounded and a linear
     * search of `medications` (and, on a miss, `refills`) per row per
     * recomposition is O(n·m).
     */
    val openRefills = MutableStateFlow<Map<String, RefillStatus>>(emptyMap())

    /** A localized outcome: a string resource plus an optional detail argument. */
    data class Outcome(val resId: Int, val detail: String? = null)
    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome
    fun clearOutcome() { _outcome.value = null }

    /**
     * Declared ABOVE `init` on purpose: Kotlin runs property initialisers and
     * init blocks in declaration order, so with this below it the initial
     * `load()` set the job and the initialiser then reset it to null — and a
     * reload from `requestRefill`/`cancelRefill` during that first load
     * overlapped it after all.
     */
    private var loadJob: Job? = null

    init { load() }

    /**
     * Returns the Job: a caller that has to READ the refreshed lists — the
     * refill-refusal branch below — must join it, because
     * `viewModelScope.launch` returns at the first suspension point and the
     * flows still hold the pre-request values. Such a caller wants
     * [awaitFreshLoad], not this: the job returned here may be one that was
     * already in flight.
     */
    fun load(): Job {
        // One at a time. There are now four triggers (init, three per-tab
        // retries) plus the joined reload in requestRefill/cancelRefill: two
        // overlapping loads both set isLoading, the first to finish clears it
        // while the other is still running, and the older response's
        // reportLoadOutcome can overwrite the newer one's.
        loadJob?.takeIf { it.isActive }?.let { return it }
        val job = viewModelScope.launch {
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
                // Snapshot BEFORE the assignments below: "showing what was
                // last loaded" is only true of data that was on screen
                // already, and a cold open whose medications call succeeds
                // would otherwise satisfy the check with rows it just
                // fetched.
                val hadDataBefore = medications.value.isNotEmpty() ||
                    prescriptions.value.isNotEmpty() ||
                    refills.value.isNotEmpty()
                mResp.body()?.data?.let { medications.value = it }
                pResp.body()?.data?.let { prescriptions.value = it }
                rResp.body()?.data?.content?.let { refills.value = it }
                rebuildOpenRefills()
                reportLoadOutcome(
                    LoadFailures(
                        medications = !mResp.isSuccessful,
                        prescriptions = !pResp.isSuccessful,
                        refills = !rResp.isSuccessful
                    ),
                    hadDataBefore
                )
            } catch (e: Exception) {
                // The patient is told what matters — the lists are stale — in
                // their own language, but the reason must not vanish: a parse
                // failure here IS a wire-contract break, the class of bug this
                // whole change exists to fix.
                Log.w(TAG, "Medications load failed", e)
                reportLoadOutcome(
                    LoadFailures(medications = true, prescriptions = true, refills = true),
                    medications.value.isNotEmpty() ||
                        prescriptions.value.isNotEmpty() ||
                        refills.value.isNotEmpty()
                )
            }
            finally { isLoading.value = false }
        }
        loadJob = job
        return job
    }

    /**
     * Keeping the previous lists removed the only signal a refresh had failed
     * — the screen used to empty. Say so instead, so an expired session is not
     * a silent no-op. On a COLD open there is nothing stale to show, so the
     * empty states offer a retry rather than claiming old data is on screen.
     */
    /**
     * The medications pass runs second and OVERWRITES, including with nothing:
     * `false` there is an answer, not a miss. Falling back to the refills page
     * on it would let a row the patient has just cancelled — kept by `load()`
     * when only that fetch failed — hide the button and tell them a withdrawn
     * request is still with their care team. The opposite staleness merely
     * costs a 400 the patient is then told about, so this is the safer way to
     * be wrong.
     */
    private fun rebuildOpenRefills() {
        val index = mutableMapOf<String, RefillStatus>()
        for (refill in refills.value) {
            val id = refill.prescriptionId ?: continue
            if (refill.statusEnum.isOpen) index.putIfAbsent(id, refill.statusEnum)
        }
        for (medication in medications.value) {
            val id = medication.id.takeIf { it.isNotBlank() } ?: continue
            val open = medication.openRefillStatus
            if (open != null) index[id] = open else index.remove(id)
        }
        openRefills.value = index
    }

    private fun reportLoadOutcome(failures: LoadFailures, hadDataBefore: Boolean) {
        loadFailed.value = failures
        // `hadDataBefore` counts ANY list — including refills, whose tab may
        // be the only populated thing a patient with no active prescriptions
        // has — and is measured before this load wrote anything.
        if (failures.any && hadDataBefore) {
            _outcome.value = Outcome(R.string.refresh_failed)
        }
    }

    /**
     * A load whose reads are guaranteed to have been issued AFTER this call.
     *
     * `load()` deduplicates, so a mutation that simply joined it could be
     * handed a job whose GETs went out before its own POST — the caller would
     * then decide the button state, or pick the refusal message, from
     * pre-mutation data.
     *
     * The in-flight load is CANCELLED rather than waited out: its answers are
     * about to be superseded, so letting it finish would cost the patient two
     * full round-trips before their confirmation snackbar — six GETs if they
     * tap Request refill while the cold-open load is still running.
     * `cancelAndJoin` lets its `finally` settle `isLoading` before the fresh
     * one raises it again, and a cancelled load never reaches
     * `reportLoadOutcome`, so it cannot overwrite the new one's. Bypassing
     * the dedupe outright is what this avoids: two concurrent loads are
     * exactly what the guard exists to prevent. `viewModelScope` is
     * main-dispatched, so the cancel and the relaunch cannot interleave with
     * another caller.
     */
    private suspend fun awaitFreshLoad() {
        loadJob?.cancelAndJoin()
        load().join()
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
                    awaitFreshLoad()
                    _outcome.value = Outcome(R.string.refill_cancelled)
                } else {
                    // Reload on refusal too: `cancelMyRefill` refuses a request
                    // the provider has already acted on, and without this the
                    // stale REQUESTED badge and its Cancel button stay on
                    // screen, so the patient taps into the same 400 forever.
                    val detail = serverMessage(resp.errorBody()?.string())
                    awaitFreshLoad()
                    _outcome.value =
                        Outcome(R.string.refill_cancel_failed, detail ?: "HTTP ${resp.code()}")
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
                    awaitFreshLoad()
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
                    awaitFreshLoad()
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
                    val openRefill = openRefills.value[prescriptionId]
                        ?: refills.value.firstOrNull {
                            it.prescriptionId == prescriptionId && it.statusEnum.isOpen
                        }?.statusEnum
                    // Only a 400 is the business refusal these two explain. A
                    // 401 with a stale `refillRequestOpen` would otherwise tell
                    // the patient their request is "already with your care team"
                    // and give them no reason to sign in again.
                    val businessRefusal = resp.code() == 400
                    _outcome.value = when {
                        businessRefusal && !stillRefillable -> Outcome(R.string.refill_not_refillable)
                        businessRefusal && openRefill != null -> Outcome(openRefillMessage(openRefill))
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
