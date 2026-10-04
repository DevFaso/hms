package com.example.hms.service;

import com.example.hms.model.LabResult;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientDiagnosis;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.payload.dto.clinical.PatientSnapshotDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.repository.PatientDiagnosisRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.CrossHospitalRows;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.SensitivityClassifier;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.model.Encounter;
import com.example.hms.model.PatientAllergy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import com.example.hms.service.recordaccess.BreakGlassGate;

/**
 * Builds the patient-snapshot DTO for the chart-summary view.
 *
 * <p>Sonar S3776 — the previous monolithic {@code getSnapshot} sat at cognitive
 * complexity 65 (and tripped Brain Method). The eight section builders below
 * each own one DTO field and are short, individually testable, and swallow
 * their own DB errors so a flaky non-essential section can't blank the whole
 * snapshot. {@code getSnapshot} is now a thin assembler.
 *
 * <p>{@link #getSnapshot} refuses a null hospital scope before it reads
 * anything, and the patient-wide finders the builders used to fall back to on
 * that null are removed rather than merely unreachable, so a later caller
 * cannot reintroduce one.
 *
 * <p><b>This is not a clean bill of health, and the list below is the whole
 * truth rather than the flattering part of it.</b> Hospital scope and
 * sensitivity (D3) are two different filters, and the sections do not all get
 * both.
 *
 * <p>Patient-wide — not scoped at all: allergies, by design (E9 #56): an
 * allergy is a property of the patient, not of the hospital that recorded it.
 *
 * <p>The legacy {@code clinical.patient_diagnoses} rows in
 * {@link #buildActiveDiagnoses} are scoped to the readable set since V171
 * gave the table a {@code hospital_id}; the rows written before it carry
 * none and are shown only to a verified super-admin in global view.
 *
 * <p>Scoped to the readable set, but <b>with no sensitivity test</b>: active
 * medications, recent vitals, latest labs, pending orders and legacy diagnoses.
 * {@code SensitivityClassifier} has {@code effectiveCategory} overloads for
 * {@code Encounter}, {@code Admission}, {@code Consultation},
 * {@code PatientProblem} and {@code NursingNote} only, so there is nothing to
 * pass {@code CrossHospitalRows.maySurface} for a {@code Prescription},
 * {@code PatientVitalSign}, {@code LabResult}, {@code LabOrder} or a legacy
 * {@code PatientDiagnosis}. The
 * consequence, stated plainly because the omission is invisible at the call
 * site: a foreign row in a sensitive category (HIV, behavioural health,
 * substance use, reproductive health) is <b>withheld</b> when it is an
 * encounter or a problem and <b>surfaces</b> when it is a prescription, a
 * vital, a lab result or an order. That is pre-existing — building those
 * overloads is its own change — but it is not a guarantee this class makes,
 * and nothing here should be read as one.
 *
 * <p>Scoped AND sensitivity-tested: encounters (and the notes and care team
 * derived from them) and the {@code patient_problems} half of the active
 * diagnoses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatientSnapshotServiceImpl implements PatientSnapshotService {

    private final com.example.hms.service.support.PatientChartAccess patientChartAccess;
    private final PatientAllergyRepository patientAllergyRepository;
    private final PatientVitalSignRepository patientVitalSignRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final EncounterRepository encounterRepository;
    private final PatientDiagnosisRepository patientDiagnosisRepository;
    private final PatientProblemRepository patientProblemRepository;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final SensitivityClassifier sensitivityClassifier;
    private final BreakGlassGate breakGlassGate;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String DIAGNOSIS_STATUS_ACTIVE = "ACTIVE";
    private static final String FLAG_NORMAL = "NORMAL";
    private static final String FLAG_REVIEW = "REVIEW";

    /**
     * Resolvable message key, not a sentence, and the same one
     * {@code PatientChartAccess}, {@code PatientLabResultServiceImpl} and
     * {@code LabOrderServiceImpl} throw: a scopeless read of one patient's
     * record is refused identically wherever the chart asks for it, and says
     * nothing about whether the patient exists.
     */
    private static final String MSG_PATIENT_NOT_FOUND = "patient.notFound";

    @Override
    public PatientSnapshotDTO getSnapshot(UUID patientId, UUID hospitalId) {
        log.info("Building patient snapshot for: {}", patientId);

        if (hospitalId == null) {
            // One patient's whole record — allergies, diagnoses, medications,
            // vitals, labs, pending orders, notes and care team — asked for
            // with no acting hospital.
            //
            // Every section below used to take a patient-wide branch on this
            // null: the readable set was never computed, the registration check
            // was skipped, and `account()` no-ops on a null acting hospital, so
            // not one foreign row was disclosed. The drawer returned the
            // patient's record from every tenant, unaccounted.
            //
            // Unlike the sibling reads this closes, the null here is NOT a
            // super-admin's, or not only. MeController resolves it with
            // `resolveHospitalId(auth).orElse(null)`, whose step 2 falls back to
            // the caller's NEWEST active assignment — for a super-admin too, so a
            // platform admin holding any clinical assignment is silently scoped to
            // it and never lands here. Who does: a caller for whom neither an
            // X-Hospital-Id nor any active assignment resolves. An ordinary
            // clinician reaches that (a JWT outliving the assignment it was minted
            // from, an assignment with no hospital, a principal the username
            // lookup misses), which is why this is not just a global-view guard.
            //
            // The accounting half cannot be patched in place: a RECORD_SHARE row
            // pairs a SOURCE hospital with an ACTING one, and in global view
            // there is no acting hospital for the disclosure to name. Unscoped
            // and accounted is not a state this endpoint can be in.
            //
            // Refusing is also what makes closing the two lab reads worth
            // anything. #735 (GET /patients/{id}/lab-results) and #739
            // (GET /lab-orders?patientId=) refuse a scopeless caller, and this
            // drawer served the same rows to the same caller through a different
            // door: buildPendingOrders called labOrderRepository.findByPatient_Id,
            // the very finder #739 abandons. This guard is right whether or not
            // those land: an unaccounted cross-tenant read is not made
            // acceptable by a sibling still serving one.
            //
            // Explicit, rather than relying on the chart-read rule below to
            // produce nothing. PatientChartAccess.require denies a null scope
            // for everyone but a super-admin, and for a super-admin
            // readableHospitalIds returns an EMPTY set on a null acting
            // hospital, so the scoped finders below would come back empty and
            // the caller would be handed a hollow drawer presented as the
            // patient's record. Rendering a failure to establish scope as "this
            // patient has nothing" is the one thing this repo has ruled out
            // repeatedly; say so with a status code instead.
            //
            // 404, and the same answer PatientChartAccess gives for a patient
            // this caller may not read or that does not exist: a caller who
            // could not establish scope learns nothing about whether the patient
            // exists.
            //
            // Logged, because the response is deliberately opaque and a
            // scope-resolution failure and a genuine missing patient are very
            // different operational events. The patient id only — it is already
            // the subject of the request, and nothing about the caller's tenancy
            // belongs in a line a 404 spike is triaged from.
            log.warn("Patient snapshot refused: no hospital scope resolved for patient {}", patientId);
            throw new com.example.hms.exception.ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patientId);
        }

        // The one chart-read rule, not a second one. This used to be
        // findByIdUnscoped + `patient.isRegisteredInHospital(hospitalId)`, which
        // diverged from PatientChartAccess in three ways that all mattered once
        // the null guard above made it the ONLY authorization on this endpoint:
        //
        //  - it threw BusinessException (400) with raw English prose, so "exists
        //    but not registered here" was distinguishable from "no such patient"
        //    (400 vs 404) and a scoped clinician could probe patient ids across
        //    the platform — which would have made the 404 chosen above
        //    pointless;
        //  - it ignored patient.isChartRestricted(), so a restricted chart's
        //    whole drawer opened on registration alone, while every chart tab
        //    throws ChartRestrictedException (E8 #54);
        //  - it had no treatment-relationship fallback, so a clinician at B
        //    treating a patient registered only at A was refused the drawer
        //    while the chart tabs opened for them (E9 #58) — the very rows the
        //    readable set below exists to surface.
        Patient patient = patientChartAccess.require(patientId, hospitalId);

        // E9 #60 — the snapshot follows the patient: every section reads the
        // policy's readable set (allergies stay patient-wide, #56) and every
        // foreign row surfaced is accounted once for the whole snapshot. A
        // foreign encounter in a sensitive category (D3) is withheld.
        UUID requesterUserId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, hospitalId);
        // E9 #62 — a live break-the-glass session unlocks the foreign sensitive
        // rows the D3 rule withholds; the ledger row names the session.
        boolean unlocked = breakGlassGate.isUnlocked(requesterUserId, patientId, hospitalId);
        Map<String, Long> reach = new HashMap<>();
        // #751 — lab results this hospital's laboratory performed for another
        // hospital are their own disclosure reason, never counted in both.
        Map<String, Long> performedHereReach = new HashMap<>();
        List<Encounter> encounters = loadEncounters(patientId, hospitalId, readable, reach, unlocked);
        PatientSnapshotDTO snapshot = PatientSnapshotDTO.builder()
                .patientId(patient.getId())
                .name(patient.getFirstName() + " " + patient.getLastName())
                .age(computeAge(patient))
                .sex(patient.getGender())
                .mrn(patient.getId().toString())
                .codeStatus(patient.getCodeStatus())
                .allergies(buildAllergies(patientId, patient, hospitalId, reach))
                .activeDiagnoses(buildActiveDiagnoses(patientId, patient, hospitalId, readable, reach, unlocked))
                .activeMedications(buildActiveMedications(patientId, hospitalId, readable, reach))
                .recentVitals(buildRecentVitals(patientId, hospitalId, readable, reach))
                .latestLabs(buildLatestLabs(patientId, hospitalId, readable, reach, performedHereReach))
                .pendingOrders(buildPendingOrders(patientId, hospitalId, readable, reach))
                .recentNotes(buildRecentNotes(encounters))
                .careTeam(buildCareTeam(encounters))
                .build();
        reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null, reach,
                "Cross-hospital patient snapshot read on the treatment relationship");
        reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null, performedHereReach,
                CrossHospitalReachRecorder.LAB_RESULT_PERFORMED_HERE_DESCRIPTION);
        return snapshot;
    }

    /**
     * E9 #60 — one row per foreign hospital surfaced, merged into the snapshot's
     * reach.
     *
     * <p>No null check on the acting hospital any more. It used to be the thing
     * that made an unscoped read silently unaccounted, and it is now
     * unreachable: {@link #getSnapshot} refuses a null before any section runs.
     * Leaving it would be the same surviving null branch this class removed six
     * times over.
     */
    private static void account(Map<String, Long> reach, UUID actingHospitalId, List<UUID> sourceHospitalIds) {
        CrossHospitalReachRecorder.merge(reach, CrossHospitalReachRecorder.reachOf(sourceHospitalIds, actingHospitalId));
    }

    private List<Encounter> loadEncounters(UUID patientId, UUID hospitalId, Set<UUID> readable, Map<String, Long> reach,
                                           boolean unlocked) {
        try {
            List<Encounter> rows = encounterRepository
                    .findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc(patientId, readable).stream()
                        .filter(e -> CrossHospitalRows.maySurface(e.getHospital(), hospitalId, sensitivityClassifier.effectiveCategory(e), unlocked))
                        .toList();
            account(reach, hospitalId, rows.stream().map(e -> CrossHospitalReachRecorder.hospitalIdOf(e.getHospital())).toList());
            return rows;
        } catch (Exception e) {
            log.debug("Encounter query error: {}", e.getMessage());
            return List.of();
        }
    }

    private int computeAge(Patient patient) {
        return patient.getDateOfBirth() != null
                ? Period.between(patient.getDateOfBirth(), LocalDate.now()).getYears()
                : 0;
    }

    private List<String> buildAllergies(UUID patientId, Patient patient, UUID hospitalId, Map<String, Long> reach) {
        List<String> allergies = new ArrayList<>();
        try {
            List<PatientAllergy> rows = patientAllergyRepository.findByPatient_Id(patientId);
            rows.forEach(a -> allergies.add(a.getAllergenDisplay()));
            account(reach, hospitalId, rows.stream().map(a -> CrossHospitalReachRecorder.hospitalIdOf(a.getHospital())).toList());
        } catch (Exception e) {
            log.debug("Allergy query error", e);
        }
        // E9 #56 — the free-text column is a derived summary of the rows above;
        // it is read only when there is no structured row at all (a patient the
        // startup backfill could not place at a hospital), never in addition.
        if (allergies.isEmpty() && patient.getAllergies() != null && !patient.getAllergies().isBlank()) {
            allergies.add(patient.getAllergies());
        }
        return allergies;
    }

    /**
     * Active diagnoses for the snapshot, from BOTH stores.
     *
     * <p>{@code clinical.patient_problems} is where every current write path
     * lands, so reading only {@code patient_diagnoses} meant the snapshot
     * showed nothing recorded since that table was superseded, then silently
     * fell through to the legacy free-text chronic conditions as if the
     * patient had no structured diagnoses at all. {@code patient_diagnoses}
     * is still read for V14-era rows and is read-only legacy.
     */
    private List<String> buildActiveDiagnoses(UUID patientId, Patient patient, UUID hospitalId,
                                              Set<UUID> readable, Map<String, Long> reach, boolean unlocked) {
        List<String> diagnoses = new ArrayList<>();
        try {
            List<com.example.hms.model.PatientProblem> problems = patientProblemRepository
                    .findByPatient_IdAndHospital_IdIn(patientId, readable).stream()
                        .filter(p -> p.getStatus() == ProblemStatus.ACTIVE)
                        .filter(p -> CrossHospitalRows.maySurface(p.getHospital(), hospitalId, sensitivityClassifier.effectiveCategory(p), unlocked))
                        .toList();
            account(reach, hospitalId, problems.stream().map(p -> CrossHospitalReachRecorder.hospitalIdOf(p.getHospital())).toList());
            problems.stream()
                    .map(p -> formatDiagnosis(p.getProblemCode(), p.getProblemDisplay()))
                    .forEach(diagnoses::add);
            // clinical.patient_diagnoses (V14) had no hospital_id until V171,
            // so this used to be the one patient-wide clinical read in this
            // class. Now it is scoped to `readable` and accounted like the
            // problems above. The rows written before V171 carry no hospital:
            // they cannot be scoped or named in the reach, so they reach this
            // drawer only for a verified super-admin in global view (the one
            // resolver's Global scope: a live SUPER_ADMIN assignment, never the
            // inflated authorities; a super-admin pinned to one hospital acts
            // at that hospital and does not get them) — and the patient still
            // sees them on their own record through the portal. The hospital
            // is never derived from diagnosedBy: that is the subject's, and
            // scope is the caller's.
            // No sensitivity test: SensitivityClassifier has no overload for a
            // diagnosis row, the same stated gap as medications and labs.
            List<PatientDiagnosis> legacy = new ArrayList<>(patientDiagnosisRepository
                    .findByPatient_IdAndStatusAndHospital_IdInOrderByDiagnosedAtDesc(
                            patientId, DIAGNOSIS_STATUS_ACTIVE, readable));
            account(reach, hospitalId, legacy.stream()
                    .map(d -> CrossHospitalReachRecorder.hospitalIdOf(d.getHospital())).toList());
            if (ActingScopeResolver.scopeOf(HospitalContextHolder.getContextOrEmpty())
                    instanceof ActingScope.Global) {
                legacy.addAll(patientDiagnosisRepository
                        .findByPatient_IdAndStatusAndHospitalIsNullOrderByDiagnosedAtDesc(
                                patientId, DIAGNOSIS_STATUS_ACTIVE));
            }
            legacy.stream()
                    .map(d -> formatDiagnosis(d.getIcdCode(), d.getDescription()))
                    .forEach(diagnoses::add);
        } catch (RuntimeException e) {
            // WARN, not debug: this used to hide a query failure behind the
            // same empty result an absent diagnosis produces, so a broken
            // read looked identical to a patient with no problems.
            log.warn("Active-diagnosis lookup failed for patient {}", patientId, e);
        }
        if (diagnoses.isEmpty()) {
            appendLegacyChronicConditions(diagnoses, patient);
        }
        return diagnoses;
    }

    /** "CODE – description", or the description alone when no code was coded. */
    private String formatDiagnosis(String code, String description) {
        return code != null ? code + " – " + description : description;
    }

    private void appendLegacyChronicConditions(List<String> diagnoses, Patient patient) {
        String conditions = patient.getChronicConditions();
        if (conditions == null || conditions.isBlank()) {
            return;
        }
        for (String cond : conditions.split("[,;]")) {
            String trimmed = cond.trim();
            if (!trimmed.isEmpty()) {
                diagnoses.add(trimmed);
            }
        }
    }

    private List<PatientSnapshotDTO.MedicationItem> buildActiveMedications(UUID patientId, UUID hospitalId,
                                                                          Set<UUID> readable, Map<String, Long> reach) {
        List<PatientSnapshotDTO.MedicationItem> medications = new ArrayList<>();
        try {
            List<com.example.hms.model.Prescription> rows = prescriptionRepository
                    .findByPatient_IdAndHospital_IdIn(patientId, readable, PageRequest.of(0, 10))
                    .getContent();
            account(reach, hospitalId, rows.stream().map(rx -> CrossHospitalReachRecorder.hospitalIdOf(rx.getHospital())).toList());
            rows.forEach(rx -> medications.add(PatientSnapshotDTO.MedicationItem.builder()
                            .name(rx.getMedicationName())
                            .dose(rx.getDosage())
                            .frequency(rx.getFrequency())
                            .build()));
        } catch (Exception e) {
            log.debug("Prescription query error: {}", e.getMessage());
        }
        return medications;
    }

    private List<PatientSnapshotDTO.VitalItem> buildRecentVitals(UUID patientId, UUID hospitalId,
                                                                Set<UUID> readable, Map<String, Long> reach) {
        List<PatientSnapshotDTO.VitalItem> vitals = new ArrayList<>();
        try {
            List<PatientVitalSign> rows = patientVitalSignRepository
                    .findByPatient_IdAndHospital_IdInOrderByRecordedAtDesc(patientId, readable, PageRequest.of(0, 5));
            account(reach, hospitalId, rows.stream().map(v -> CrossHospitalReachRecorder.hospitalIdOf(v.getHospital())).toList());
            rows.forEach(v -> vitals.add(PatientSnapshotDTO.VitalItem.builder()
                            .type("VITALS")
                            .value(summarizeVitals(v))
                            .timestamp(v.getRecordedAt() != null ? v.getRecordedAt().format(DATE_FMT) : "")
                            .build()));
        } catch (Exception e) {
            log.debug("Vitals query error: {}", e.getMessage());
        }
        return vitals;
    }

    private String summarizeVitals(PatientVitalSign v) {
        StringBuilder s = new StringBuilder();
        if (v.getTemperatureCelsius() != null) s.append("T:").append(v.getTemperatureCelsius()).append("°C ");
        if (v.getHeartRateBpm() != null) s.append("HR:").append(v.getHeartRateBpm()).append(" ");
        if (v.getSystolicBpMmHg() != null && v.getDiastolicBpMmHg() != null) {
            s.append("BP:").append(v.getSystolicBpMmHg()).append("/").append(v.getDiastolicBpMmHg()).append(" ");
        }
        if (v.getSpo2Percent() != null) s.append("SpO2:").append(v.getSpo2Percent()).append("% ");
        return s.toString().trim();
    }

    private List<PatientSnapshotDTO.LabItem> buildLatestLabs(UUID patientId, UUID hospitalId,
                                                            Set<UUID> readable, Map<String, Long> reach,
                                                            Map<String, Long> performedHereReach) {
        List<PatientSnapshotDTO.LabItem> labs = new ArrayList<>();
        try {
            // Paged at the DB so the patient's full lab history is never loaded
            // to trim to 10 after the fact — the LATEST 10 (newest result first,
            // id breaking ties; the page had no order, so "latest" was not
            // guaranteed). Readable where its order is handled (#751): ordered
            // in the readable set, or performed by this hospital's laboratory,
            // as on the other staff lab views.
            List<LabResult> rows = labResultRepository.findPatientResultsReadableAt(patientId, readable, hospitalId,
                    false, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "resultDate", "id")));
            account(reach, hospitalId, rows.stream()
                    .filter(r -> !CrossHospitalReachRecorder.isPerformedHere(r, hospitalId))
                    .map(PatientSnapshotServiceImpl::orderingHospitalIdOf)
                    .toList());
            account(performedHereReach, hospitalId, rows.stream()
                    .filter(r -> CrossHospitalReachRecorder.isPerformedHere(r, hospitalId))
                    .map(PatientSnapshotServiceImpl::orderingHospitalIdOf)
                    .toList());
            // No test definition (deleted, or never linked) sends null rather
            // than an English word: the drawer renders its own translated
            // fallback, as it already does for the pending order below.
            rows.forEach(r -> labs.add(PatientSnapshotDTO.LabItem.builder()
                            .test(r.getLabOrder().getLabTestDefinition() != null
                                    ? r.getLabOrder().getLabTestDefinition().getName()
                                    : null)
                            .value(r.getResultValue())
                            .flag(labResultFlag(r))
                            .abnormalDirection(r.getAbnormalFlag() != null ? r.getAbnormalFlag().direction() : null)
                            .date(r.getResultDate() != null ? r.getResultDate().format(DATE_FMT) : "")
                            .build()));
        } catch (Exception e) {
            log.debug("Lab results query error: {}", e.getMessage());
        }
        return labs;
    }

    private static UUID orderingHospitalIdOf(LabResult result) {
        return result.getLabOrder() == null ? null
                : CrossHospitalReachRecorder.hospitalIdOf(result.getLabOrder().getHospital());
    }

    /** The three-value family: the drawer colours on the literal ABNORMAL / CRITICAL. */
    private String labResultFlag(LabResult r) {
        if (r.getAbnormalFlag() != null) {
            return r.getAbnormalFlag().severity().name();
        }
        return r.isAcknowledged() ? FLAG_NORMAL : FLAG_REVIEW;
    }

    private List<PatientSnapshotDTO.OrderItem> buildPendingOrders(UUID patientId, UUID hospitalId,
                                                                 Set<UUID> readable, Map<String, Long> reach) {
        List<PatientSnapshotDTO.OrderItem> pendingOrders = new ArrayList<>();
        try {
            List<com.example.hms.model.LabOrder> rows =
                    labOrderRepository.findByPatient_IdAndHospital_IdIn(patientId, readable);
            account(reach, hospitalId, rows.stream().map(o -> CrossHospitalReachRecorder.hospitalIdOf(o.getHospital())).toList());
            rows.stream()
                    .filter(o -> o.getStatus() == com.example.hms.enums.LabOrderStatus.PENDING
                            || o.getStatus() == com.example.hms.enums.LabOrderStatus.IN_PROGRESS)
                    .limit(10)
                    .forEach(o -> pendingOrders.add(PatientSnapshotDTO.OrderItem.builder()
                            .type("LAB")
                            .description(o.getLabTestDefinition() != null ? o.getLabTestDefinition().getName() : null)
                            .orderedAt(o.getOrderDatetime() != null ? o.getOrderDatetime().format(DATE_FMT) : "")
                            .build()));
        } catch (Exception e) {
            log.debug("Pending orders query error: {}", e.getMessage());
        }
        return pendingOrders;
    }

    private List<PatientSnapshotDTO.CareTeamMember> buildCareTeam(List<Encounter> encounters) {
        List<PatientSnapshotDTO.CareTeamMember> careTeam = new ArrayList<>();
        try {
            encounters.stream()
                    .filter(e -> e.getStaff() != null)
                    .map(e -> PatientSnapshotDTO.CareTeamMember.builder()
                            .role(e.getStaff().getJobTitle() != null ? e.getStaff().getJobTitle().name() : null)
                            .name(e.getStaff().getFullName())
                            .build())
                    .distinct()
                    .limit(10)
                    .forEach(careTeam::add);
        } catch (Exception e) {
            log.debug("Care team query error: {}", e.getMessage());
        }
        return careTeam;
    }

    private List<PatientSnapshotDTO.NoteItem> buildRecentNotes(List<Encounter> encounters) {
        List<PatientSnapshotDTO.NoteItem> recentNotes = new ArrayList<>();
        try {
            encounters.stream()
                    .filter(e -> e.getNotes() != null && !e.getNotes().isBlank())
                    .sorted(java.util.Comparator.comparing(
                            e -> e.getEncounterDate() != null ? e.getEncounterDate() : java.time.LocalDateTime.MIN,
                            java.util.Comparator.reverseOrder()))
                    .limit(5)
                    .forEach(e -> recentNotes.add(PatientSnapshotDTO.NoteItem.builder()
                            .author(e.getStaff() != null ? e.getStaff().getFullName() : null)
                            .type(e.getEncounterType() != null ? e.getEncounterType().name() : null)
                            .date(e.getEncounterDate() != null ? e.getEncounterDate().format(DATE_FMT) : "")
                            .snippet(truncateSnippet(e.getNotes()))
                            .build()));
        } catch (Exception e) {
            log.debug("Recent notes query error: {}", e.getMessage());
        }
        return recentNotes;
    }

    private String truncateSnippet(String notes) {
        return notes.length() > 200 ? notes.substring(0, 200) + "…" : notes;
    }
}
