package com.bitnesttechs.hms.patient.features.medicalhistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.di.ApplicationScope
import com.bitnesttechs.hms.patient.core.models.FamilyHistoryEntry
import com.bitnesttechs.hms.patient.core.models.PatientDiagnosisSummary
import com.bitnesttechs.hms.patient.core.models.SocialHistory
import com.bitnesttechs.hms.patient.core.models.SurgicalHistoryEntry
import com.bitnesttechs.hms.patient.core.network.ApiResponse
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import javax.inject.Inject

/**
 * The web's My Medical History: what the care team recorded (diagnoses,
 * surgeries, family and social history, all read-only for the patient) plus
 * the patient's own device-only notes under each section. The web loads the
 * four sections as one all-or-nothing request; here each section loads,
 * fails and retries on its own so one bad answer never blanks the rest.
 */
@HiltViewModel
class MedicalHistoryViewModel @Inject constructor(
    private val api: ApiService,
    private val notes: HistoryNotesStore,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {

    enum class Section(val storageKey: String) {
        MEDICAL("medical"), SURGICAL("surgical"), FAMILY("family"), SOCIAL("social")
    }

    /** Loading, failed and "nothing on record" are three different states; `data` is null until loaded. */
    data class SectionState<T>(
        val loading: Boolean = true,
        val failed: Boolean = false,
        val data: T? = null
    )

    enum class TobaccoStatus { CURRENT, FORMER, NEVER }

    data class UiState(
        val medical: SectionState<List<PatientDiagnosisSummary>> = SectionState(),
        val surgical: SectionState<List<SurgicalHistoryEntry>> = SectionState(),
        val family: SectionState<List<FamilyHistoryEntry>> = SectionState(),
        /** `data` stays null after a successful load when nothing is on record. */
        val social: SectionState<SocialHistory> = SectionState(),
        val notes: Map<Section, String> = emptyMap(),
        val editing: Section? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadNotes()
        loadAll()
    }

    fun loadAll() {
        loadMedical(); loadSurgical(); loadFamily(); loadSocial()
    }

    fun loadMedical() = loadSection(
        Section.MEDICAL, get = { it.medical }, set = { s, v -> s.copy(medical = v) },
        call = { api.getMyMedicalHistory() }, orEmpty = { emptyList() }
    )

    fun loadSurgical() = loadSection(
        Section.SURGICAL, get = { it.surgical }, set = { s, v -> s.copy(surgical = v) },
        call = { api.getMySurgicalHistory() }, orEmpty = { emptyList() }
    )

    fun loadFamily() = loadSection(
        Section.FAMILY, get = { it.family }, set = { s, v -> s.copy(family = v) },
        call = { api.getMyFamilyHistory() }, orEmpty = { emptyList() }
    )

    fun loadSocial() = loadSection(
        Section.SOCIAL, get = { it.social }, set = { s, v -> s.copy(social = v) },
        call = { api.getMySocialHistory() }, orEmpty = { null }
    )

    /** Sections with a request out right now; a second Retry tap while one is pending is ignored. */
    private val inFlight = mutableSetOf<Section>()

    /**
     * One section, on its own: a 2xx with a null `data` is "nothing on record"
     * (`orEmpty`), anything else is a failure the section shows with a Retry.
     * What was shown before a failed retry stays on screen.
     */
    private fun <T> loadSection(
        section: Section,
        get: (UiState) -> SectionState<T>,
        set: (UiState, SectionState<T>) -> UiState,
        call: suspend () -> Response<ApiResponse<T>>,
        orEmpty: () -> T?
    ) {
        if (!inFlight.add(section)) return
        _state.update { set(it, get(it).copy(loading = true, failed = false)) }
        viewModelScope.launch {
            try {
                val resp = call()
                val body = resp.body()
                if (resp.isSuccessful && body != null && body.success) {
                    _state.update { set(it, SectionState(loading = false, failed = false, data = body.data ?: orEmpty())) }
                } else {
                    _state.update { set(it, get(it).copy(loading = false, failed = true)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { set(it, get(it).copy(loading = false, failed = true)) }
            } finally {
                inFlight.remove(section)
            }
        }
    }

    // ── Personal notes (device only) ─────────────────────────────────────

    private fun loadNotes() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                Section.values().associateWith { notes.read(it.storageKey) }
            }
            // A note the patient started typing before the read finished wins.
            _state.update { s -> s.copy(notes = loaded + s.notes.filterValues { it.isNotEmpty() }) }
        }
    }

    /**
     * The web's toggleNoteEdit: opens the section's editor, or saves and closes
     * it when it is the one open. Opening another section saves the one that
     * was open first, so nothing typed is lost to a stray tap.
     */
    fun toggleNoteEdit(section: Section) {
        val s = _state.value
        val open = s.editing
        if (open == section) {
            persist(section, s.notes[section] ?: "")
            _state.update { it.copy(editing = null) }
        } else {
            if (open != null) persist(open, s.notes[open] ?: "")
            _state.update { it.copy(editing = section) }
        }
    }

    fun noteChanged(section: Section, value: String) {
        _state.update { it.copy(notes = it.notes + (section to value)) }
    }

    /** Written in [applicationScope]: a back press right after Save must not cancel the write. */
    private fun persist(section: Section, value: String) {
        val trimmed = value.trim()
        _state.update { it.copy(notes = it.notes + (section to trimmed)) }
        applicationScope.launch(Dispatchers.IO) { notes.write(section.storageKey, trimmed) }
    }

    companion object {
        /** The web's getTobaccoStatus: a quit date means former, else current if using, else never. */
        fun tobaccoStatus(sh: SocialHistory): TobaccoStatus = when {
            !sh.tobaccoQuitDate.isNullOrBlank() -> TobaccoStatus.FORMER
            sh.tobaccoUse == true -> TobaccoStatus.CURRENT
            else -> TobaccoStatus.NEVER
        }
    }
}
