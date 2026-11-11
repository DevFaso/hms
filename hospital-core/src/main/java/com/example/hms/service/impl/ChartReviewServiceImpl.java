package com.example.hms.service.impl;

import com.example.hms.service.recordaccess.NeverSentRule;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabOrder;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.Staff;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.EncounterEntryDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.ImagingEntryDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.MedicationEntryDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.NoteEntryDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.ProcedureEntryDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.ResultEntryDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.TimelineEventDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.TimelineEventDTO.Section;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.NursingNoteRepository;
import com.example.hms.model.NursingNote;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.service.lab.ClinicalLabResultView;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.service.ChartReviewService;
import com.example.hms.service.support.PatientChartAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.CrossHospitalRows;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.SensitivityClassifier;
import com.example.hms.security.context.HospitalContextHolder;
import java.util.Set;
import com.example.hms.service.recordaccess.BreakGlassGate;

/**
 * Aggregates the six clinical sections shown in the Chart Review viewer
 * (Encounters / Notes / Results / Medications / Imaging / Procedures)
 * plus a unified timeline. Queries existing repositories only — no
 * schema changes. Each section is read whole and counted, and one page of
 * it is returned so the payload stays small on metered links (QA D1380).
 */
@Service
@RequiredArgsConstructor
public class ChartReviewServiceImpl implements ChartReviewService {

    /** Length used for note / imaging-impression preview snippets. */
    static final int PREVIEW_LENGTH = 280;
    /** QA D1076: where a Notes-tab row comes from. */
    static final String NOTE_SOURCE_ENCOUNTER = "ENCOUNTER";
    static final String NOTE_SOURCE_NURSING = "NURSING";

    private final PatientChartAccess patientChartAccess;
    private final EncounterRepository encounterRepository;
    private final EncounterNoteRepository encounterNoteRepository;
    /** QA D1076: the ward's nursing notes are part of the record the chart review and its PDF show. */
    private final NursingNoteRepository nursingNoteRepository;
    private final LabResultRepository labResultRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final ImagingOrderRepository imagingOrderRepository;
    private final ImagingReportRepository imagingReportRepository;
    private final ProcedureOrderRepository procedureOrderRepository;
    private final HospitalRepository hospitalRepository;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final SensitivityClassifier sensitivityClassifier;
    private final BreakGlassGate breakGlassGate;
    /** QA D520: grades each result the way the lab-results screen does. */
    private final com.example.hms.mapper.LabResultMapper labResultMapper;

    @Override
    @Transactional(readOnly = true)
    public ChartReviewDTO getChartReview(UUID patientId, UUID hospitalId, Integer limit,
                                         ChartReviewSection section, int page) {
        int size = clampLimit(limit);
        int safePage = Math.max(page, 0);
        return assemble(patientId, hospitalId, section, (long) safePage * size, size, safePage);
    }

    @Override
    @Transactional(readOnly = true)
    public ChartReviewDTO getCompleteChartReview(UUID patientId, UUID hospitalId) {
        return assemble(patientId, hospitalId, null, 0L, Integer.MAX_VALUE, 0);
    }

    /**
     * QA D1380 (D1347 class): the tabs said CHRONOLOGIE 20, MEDICAMENTS 20 for a
     * patient with 667 prescriptions: each section was read capped at the
     * page size and its length shown as the count. Every section is now read
     * whole (the same filters as before: readable set, D3 withholding,
     * never-sent rows, superseded results), ordered on a total order, counted,
     * and only then cut to the page asked for. Only the rows on the page
     * returned are accounted as surfaced from another hospital.
     */
    private ChartReviewDTO assemble(UUID patientId, UUID hospitalId, ChartReviewSection section,
                                    long offset, int size, int page) {
        // Resolves unscoped then authorizes against the registration table —
        // the tenant-scoped findById used to 404 every cross-hospital patient
        // whose chart header had just loaded fine. See PatientChartAccess.
        Patient patient = patientChartAccess.require(patientId, hospitalId);

        // E9 #60 — the chart review follows the patient: with an acting hospital
        // every section reads the policy's readable set and the reach of the
        // whole review is accounted once. A foreign encounter in a sensitive
        // category (D3) is withheld with its note. Without an acting hospital
        // (super-admin global view) the patient-wide reads stay.
        UUID requesterUserId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        Set<UUID> readable = hospitalId == null ? null
            : recordAccessPolicy.readableHospitalIds(requesterUserId, patient.getId(), hospitalId);
        // E9 #62 — a live break-the-glass session unlocks the foreign sensitive
        // encounters the D3 rule withholds; the ledger row names the session.
        boolean unlocked = readable != null && breakGlassGate.isUnlocked(requesterUserId, patient.getId(), hospitalId);
        // Where each row came from, accounted only once the page is cut.
        // #751 — results this hospital's laboratory performed for another
        // hospital are their own disclosure reason, counted apart from the
        // treatment relationship's reach and never in both.
        RowSources sources = new RowSources();
        List<EncounterEntryDTO> encounters = loadEncounters(patient.getId(), hospitalId, readable, sources, unlocked);
        List<NoteEntryDTO> notes = new ArrayList<>(loadNotes(encounters));
        notes.addAll(loadNursingNotes(patient.getId(), readable, sources, unlocked, hospitalId));
        notes.sort(Comparator.comparing(NoteEntryDTO::getDocumentedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(NoteEntryDTO::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        List<ResultEntryDTO> results = loadResults(patient.getId(), hospitalId, readable, sources);
        List<MedicationEntryDTO> medications = loadMedications(patient.getId(), hospitalId, readable, sources);
        List<ImagingEntryDTO> imaging = loadImaging(patient.getId(), hospitalId, readable, sources);
        List<ProcedureEntryDTO> procedures = loadProcedures(patient.getId(), readable, sources);
        List<TimelineEventDTO> timeline = buildTimeline(encounters, notes, results, medications, imaging, procedures);

        ChartReviewDTO.Totals totals = ChartReviewDTO.Totals.builder()
            .timeline(timeline.size())
            .encounters(encounters.size())
            .notes(notes.size())
            .results(results.size())
            .medications(medications.size())
            .imaging(imaging.size())
            .procedures(procedures.size())
            .build();

        List<EncounterEntryDTO> encounterPage = wants(section, ChartReviewSection.ENCOUNTERS)
            ? encounters.stream().skip(offset).limit(size).toList() : List.of();
        List<NoteEntryDTO> notePage = wants(section, ChartReviewSection.NOTES)
            ? notes.stream().skip(offset).limit(size).toList() : List.of();
        List<ResultEntryDTO> resultPage = wants(section, ChartReviewSection.RESULTS)
            ? results.stream().skip(offset).limit(size).toList() : List.of();
        List<MedicationEntryDTO> medicationPage = wants(section, ChartReviewSection.MEDICATIONS)
            ? medications.stream().skip(offset).limit(size).toList() : List.of();
        List<ImagingEntryDTO> imagingPage = wants(section, ChartReviewSection.IMAGING)
            ? imaging.stream().skip(offset).limit(size).toList() : List.of();
        List<ProcedureEntryDTO> procedurePage = wants(section, ChartReviewSection.PROCEDURES)
            ? procedures.stream().skip(offset).limit(size).toList() : List.of();
        List<TimelineEventDTO> timelinePage = wants(section, ChartReviewSection.TIMELINE)
            ? timeline.stream().skip(offset).limit(size).toList() : List.of();

        if (hospitalId != null) {
            Set<UUID> surfaced = new HashSet<>();
            encounterPage.forEach(e -> surfaced.add(e.getId()));
            notePage.forEach(n -> surfaced.add(n.getId()));
            resultPage.forEach(r -> surfaced.add(r.getId()));
            medicationPage.forEach(m -> surfaced.add(m.getId()));
            imagingPage.forEach(i -> surfaced.add(i.getId()));
            procedurePage.forEach(p -> surfaced.add(p.getId()));
            timelinePage.forEach(t -> surfaced.add(t.getId()));
            reachRecorder.recordReach(patient.getId(), hospitalId, requesterUserId, null,
                sources.reach(surfaced, hospitalId),
                "Cross-hospital chart review read on the treatment relationship");
            reachRecorder.recordReach(patient.getId(), hospitalId, requesterUserId, null,
                sources.performedHereReach(surfaced, hospitalId),
                CrossHospitalReachRecorder.LAB_RESULT_PERFORMED_HERE_DESCRIPTION);
        }

        return ChartReviewDTO.builder()
            .patientId(patient.getId())
            .hospitalId(hospitalId)
            .hospitalName(resolveHospitalName(hospitalId))
            .limit(size)
            .page(page)
            .section(section != null ? section.name() : null)
            .totals(totals)
            .encounters(encounterPage)
            .notes(notePage)
            .results(resultPage)
            .medications(medicationPage)
            .imaging(imagingPage)
            .procedures(procedurePage)
            .timeline(timelinePage)
            .timelineRefs(timelineRefs(timelinePage, encounters, notes, imaging))
            .generatedAt(LocalDateTime.now())
            .build();
    }

    private static boolean wants(ChartReviewSection asked, ChartReviewSection section) {
        return asked == null || asked == section;
    }

    /** QA D1380: the entries a timeline page names, so the page labels its rows (QA D829) on any page. */
    private static ChartReviewDTO.TimelineRefs timelineRefs(List<TimelineEventDTO> timelinePage,
                                                            List<EncounterEntryDTO> encounters,
                                                            List<NoteEntryDTO> notes,
                                                            List<ImagingEntryDTO> imaging) {
        if (timelinePage.isEmpty()) {
            return null;
        }
        Set<UUID> named = new HashSet<>();
        timelinePage.forEach(t -> named.add(t.getId()));
        return ChartReviewDTO.TimelineRefs.builder()
            .encounters(encounters.stream().filter(e -> named.contains(e.getId())).toList())
            .notes(notes.stream().filter(n -> named.contains(n.getId())).toList())
            .imaging(imaging.stream().filter(i -> named.contains(i.getId())).toList())
            .build();
    }

    /**
     * QA D1380: the hospital each loaded row came from, so the reach is
     * accounted for the rows a page SHOWS, not for every row read to count
     * the section. A row that was never accounted (a visit's own clinical
     * note) is not registered.
     */
    private static final class RowSources {
        private final Map<UUID, UUID> treatmentReach = new HashMap<>();
        private final Map<UUID, UUID> performedHere = new HashMap<>();

        void treatment(UUID rowId, UUID sourceHospitalId) {
            if (rowId != null) {
                treatmentReach.put(rowId, sourceHospitalId);
            }
        }

        void performedHere(UUID rowId, UUID sourceHospitalId) {
            if (rowId != null) {
                performedHere.put(rowId, sourceHospitalId);
            }
        }

        Map<String, Long> reach(Set<UUID> surfaced, UUID actingHospitalId) {
            return reachOf(treatmentReach, surfaced, actingHospitalId);
        }

        Map<String, Long> performedHereReach(Set<UUID> surfaced, UUID actingHospitalId) {
            return reachOf(performedHere, surfaced, actingHospitalId);
        }

        private static Map<String, Long> reachOf(Map<UUID, UUID> source, Set<UUID> surfaced, UUID actingHospitalId) {
            List<UUID> hospitals = new ArrayList<>();
            for (UUID rowId : surfaced) {
                if (source.containsKey(rowId)) {
                    hospitals.add(source.get(rowId));
                }
            }
            return CrossHospitalReachRecorder.reachOf(hospitals, actingHospitalId);
        }
    }

    /* ------------- per-section loaders ------------------------------- */

    private List<EncounterEntryDTO> loadEncounters(UUID patientId, UUID hospitalId, Set<UUID> readable,
                                                   RowSources sources, boolean unlocked) {
        // QA D1380: every visit, counted; the page is cut after the count.
        Pageable page = Pageable.unpaged();
        // QA D791 (D759's sibling): a prescription's anchor is not a visit. It
        // is left out in the query, so it neither shows as a consultation nor
        // takes one of the page's rows from a real visit.
        List<Encounter> source = readable != null
            ? encounterRepository
                .findVisitsByPatientIdAndHospitalIdIn(patientId, readable, page)
                .getContent().stream()
                .filter(e -> CrossHospitalRows.maySurface(e.getHospital(), hospitalId, sensitivityClassifier.effectiveCategory(e), unlocked))
                .toList()
            : encounterRepository
                .findVisitsByPatientId(patientId, page)
                .getContent();
        source.forEach(e -> sources.treatment(e.getId(), CrossHospitalReachRecorder.hospitalIdOf(e.getHospital())));
        return source.stream()
            .map(this::toEncounterDto)
            .sorted(Comparator.comparing(EncounterEntryDTO::getEncounterDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(EncounterEntryDTO::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    /**
     * Notes are aligned to the encounters loaded on the current page (one note
     * per encounter via {@code uk_encounter_note_encounter}). Fetched in a
     * single batch query keyed by encounterId to avoid the N+1 pattern of
     * one repository call per encounter.
     */
    private List<NoteEntryDTO> loadNotes(List<EncounterEntryDTO> encounters) {
        if (encounters == null || encounters.isEmpty()) {
            return List.of();
        }
        Map<UUID, EncounterEntryDTO> encounterById = new HashMap<>();
        for (EncounterEntryDTO enc : encounters) {
            if (enc.getId() != null) {
                encounterById.put(enc.getId(), enc);
            }
        }
        if (encounterById.isEmpty()) {
            return List.of();
        }
        return encounterNoteRepository.findByEncounter_IdIn(encounterById.keySet()).stream()
            .filter(n -> n.getEncounter() != null
                && encounterById.containsKey(n.getEncounter().getId())
                && !Encounter.PRESCRIPTION_ANCHOR_NOTE.equals(n.getEncounter().getNotes()))
            .map(n -> toNoteDto(n, encounterById.get(n.getEncounter().getId())))
            .sorted(Comparator.comparing(NoteEntryDTO::getDocumentedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /**
     * QA D1076: the chart review read clinical (encounter) notes only, so the
     * Notes tab said 0 and the record PDF "Aucun élément enregistré" while the
     * ward had written nursing notes. Read as /nurse/notes reads them (E9 #59):
     * the readable set, a foreign sensitive note withheld unless unlocked, each
     * foreign note surfaced counted in the review's reach.
     */
    private List<NoteEntryDTO> loadNursingNotes(UUID patientId, Set<UUID> readable, RowSources sources,
                                                boolean unlocked, UUID hospitalId) {
        // QA D1380: every nursing note (was the 50 newest), counted.
        List<NursingNote> source = readable != null
            ? nursingNoteRepository.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(patientId, readable).stream()
                .filter(n -> CrossHospitalRows.maySurface(n.getHospital(), hospitalId,
                    sensitivityClassifier.effectiveCategory(n), unlocked))
                .toList()
            : nursingNoteRepository.findByPatient_IdOrderByCreatedAtDesc(patientId);
        source.forEach(n -> sources.treatment(n.getId(), CrossHospitalReachRecorder.hospitalIdOf(n.getHospital())));
        return source.stream().map(ChartReviewServiceImpl::toNursingNoteDto).toList();
    }

    public static NoteEntryDTO toNursingNoteDto(NursingNote note) {
        return NoteEntryDTO.builder()
            .id(note.getId())
            .template(note.getTemplate() != null ? note.getTemplate().name() : null)
            // QA D1107: the title the nurse typed, shown above the note.
            .title(note.getTitle() != null && !note.getTitle().isBlank() ? note.getTitle().strip() : null)
            .authorName(firstNonBlankOf(note.getSignedByName(), note.getAuthorName()))
            // QA D1136: a job title as its code; the portal labels it.
            .authorCredentials(com.example.hms.utility.JobTitleCredentials.toCode(
                firstNonBlankOf(note.getSignedByCredentials(), note.getAuthorCredentials())))
            .documentedAt(note.getDocumentedAt())
            .signedAt(note.getSignedAt())
            .signed(note.getSignedAt() != null)
            .lateEntry(note.isLateEntry())
            .preview(firstPreview(note.getNarrative(), note.getDataAssessment(), note.getActionSummary(),
                note.getResponseSummary(), note.getDataSubjective(), note.getDataObjective(), note.getDataPlan(),
                note.getDataImplementation(), note.getDataEvaluation(), note.getEducationSummary()))
            .source(NOTE_SOURCE_NURSING)
            .build();
    }

    private static String firstNonBlankOf(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String firstPreview(String... candidates) {
        for (String candidate : candidates) {
            String preview = truncatePreview(candidate);
            if (preview != null) {
                return preview;
            }
        }
        return null;
    }

    private List<ResultEntryDTO> loadResults(UUID patientId, UUID hospitalId, Set<UUID> readable,
                                             RowSources sources) {
        // QA D1380: every result, counted; id breaks resultDate ties, so a
        // page boundary is the same on every read.
        Pageable page = Pageable.unpaged(Sort.by(Sort.Direction.DESC, "resultDate", "id"));
        // Global view (no acting hospital) is a verified super-admin only —
        // PatientChartAccess.require refuses a null scope for anyone else — and
        // reads every hospital through findAllPatientResults: there is no
        // patient-only finder left. With an acting hospital a result is
        // readable where its order is handled (#751): ordered in the readable
        // set, or performed by this hospital's own laboratory.
        // QA D741: a superseded result and a legacy duplicate are not current.
        List<LabResult> source = ClinicalLabResultView.current(readable != null
            ? labResultRepository.findPatientResultsReadableAt(patientId, readable, hospitalId, false, page)
            : labResultRepository.findAllPatientResults(patientId, page));
        for (LabResult r : source) {
            if (CrossHospitalReachRecorder.isPerformedHere(r, hospitalId)) {
                sources.performedHere(r.getId(), orderingHospitalIdOf(r));
            } else {
                sources.treatment(r.getId(), orderingHospitalIdOf(r));
            }
        }
        return source.stream()
            .map(this::toResultDto)
            .sorted(Comparator.comparing(ResultEntryDTO::getResultDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ResultEntryDTO::getId, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private static UUID orderingHospitalIdOf(LabResult result) {
        return result.getLabOrder() == null ? null
            : CrossHospitalReachRecorder.hospitalIdOf(result.getLabOrder().getHospital());
    }

    private List<MedicationEntryDTO> loadMedications(UUID patientId, UUID hospitalId, Set<UUID> readable,
                                                     RowSources sources) {
        // QA D1380: every prescription (667 for one QA patient), counted.
        Pageable page = Pageable.unpaged(Sort.by(Sort.Direction.DESC, "createdAt"));
        // QA D652: less another hospital's never-sent prescriptions (NeverSentRule).
        // QA D1537 review: a draft withdrawn before anyone signed it was no order; not listed as CANCELLED.
        List<Prescription> source = com.example.hms.service.support.PrescriptionIssuance.withoutDiscardedProposals(readable != null
            ? NeverSentRule.prescriptionsReadableHere(prescriptionRepository
                .findByPatient_IdAndHospital_IdIn(patientId, readable, page)
                .getContent(), hospitalId)
            : prescriptionRepository
                .findByPatient_Id(patientId, page)
                .getContent());
        source.forEach(p -> sources.treatment(p.getId(), CrossHospitalReachRecorder.hospitalIdOf(p.getHospital())));
        return source.stream()
            .map(this::toMedicationDto)
            .sorted(Comparator.comparing(MedicationEntryDTO::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(MedicationEntryDTO::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    private List<ImagingEntryDTO> loadImaging(UUID patientId, UUID hospitalId, Set<UUID> readable,
                                              RowSources sources) {
        // QA D1380: every imaging order, counted.
        Pageable page = Pageable.unpaged();
        // QA D652: less another hospital's never-sent DRAFTs (NeverSentRule).
        List<ImagingOrder> orders = readable != null
            ? NeverSentRule.imagingOrdersReadableHere(imagingOrderRepository
                .findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, readable, page)
                .getContent(), hospitalId)
            : imagingOrderRepository
                .findByPatient_IdOrderByOrderedAtDesc(patientId, page)
                .getContent();
        orders.forEach(o -> sources.treatment(o.getId(), CrossHospitalReachRecorder.hospitalIdOf(o.getHospital())));
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<UUID, ImagingReport> latestByOrderId = batchLoadLatestReports(orders);
        return orders.stream()
            .map(o -> toImagingDto(o, latestByOrderId.get(o.getId())))
            .sorted(Comparator.comparing(ImagingEntryDTO::getOrderedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ImagingEntryDTO::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    /**
     * Resolves the most recent imaging report for every order on the current
     * page in (at most) two queries: one keyed by {@code latest_version=true}
     * and a fallback for legacy rows that never set the flag, where the
     * highest {@code reportVersion} per order wins. Avoids the N+1 lookup
     * pattern that the per-row {@code toImagingDto} previously caused.
     */
    private Map<UUID, ImagingReport> batchLoadLatestReports(List<ImagingOrder> orders) {
        List<UUID> orderIds = orders.stream()
            .map(ImagingOrder::getId)
            .filter(java.util.Objects::nonNull)
            .toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ImagingReport> byOrder = new HashMap<>();
        for (ImagingReport r : imagingReportRepository
            .findByImagingOrder_IdInAndLatestVersionIsTrue(orderIds)) {
            UUID oid = r.getImagingOrder() != null ? r.getImagingOrder().getId() : null;
            if (oid != null) {
                byOrder.put(oid, r);
            }
        }
        List<UUID> missing = orderIds.stream()
            .filter(id -> !byOrder.containsKey(id))
            .toList();
        if (!missing.isEmpty()) {
            for (ImagingReport r : imagingReportRepository.findByImagingOrder_IdIn(missing)) {
                UUID oid = r.getImagingOrder() != null ? r.getImagingOrder().getId() : null;
                if (oid == null) {
                    continue;
                }
                ImagingReport prior = byOrder.get(oid);
                if (prior == null
                    || compareVersion(r.getReportVersion(), prior.getReportVersion()) > 0) {
                    byOrder.put(oid, r);
                }
            }
        }
        return byOrder;
    }

    private static int compareVersion(Integer a, Integer b) {
        int aValue = a != null ? a : 0;
        int bValue = b != null ? b : 0;
        return Integer.compare(aValue, bValue);
    }

    private List<ProcedureEntryDTO> loadProcedures(UUID patientId, Set<UUID> readable, RowSources sources) {
        // QA D1380: every procedure order, counted.
        List<ProcedureOrder> orders = readable != null
            ? procedureOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, readable)
            : procedureOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId);
        orders.forEach(o -> sources.treatment(o.getId(), CrossHospitalReachRecorder.hospitalIdOf(o.getHospital())));
        return orders.stream()
            .map(this::toProcedureDto)
            .sorted(Comparator.comparing(ProcedureEntryDTO::getOrderedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ProcedureEntryDTO::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    /* ------------- timeline ------------------------------------------ */

    private List<TimelineEventDTO> buildTimeline(
        List<EncounterEntryDTO> encounters,
        List<NoteEntryDTO> notes,
        List<ResultEntryDTO> results,
        List<MedicationEntryDTO> medications,
        List<ImagingEntryDTO> imaging,
        List<ProcedureEntryDTO> procedures
    ) {
        List<TimelineEventDTO> events = new ArrayList<>();

        encounters.forEach(e -> events.add(TimelineEventDTO.builder()
            .id(e.getId())
            .section(Section.ENCOUNTER)
            .occurredAt(e.getEncounterDate())
            .title(joinNonBlank(" — ", e.getEncounterType(), e.getDepartmentName()))
            .summary(e.getChiefComplaint())
            .status(e.getStatus())
            .build()));

        notes.forEach(n -> events.add(TimelineEventDTO.builder()
            .id(n.getId())
            .section(Section.NOTE)
            .occurredAt(n.getDocumentedAt())
            // QA D1107: the note's own title leads, when it has one.
            .title(joinNonBlank(" — ", n.getTitle(), n.getTemplate(), n.getAuthorName()))
            .summary(n.getPreview())
            .status(n.isSigned() ? "SIGNED" : "DRAFT")
            .build()));

        // Title carries test name + value + unit; status pill carries the abnormal
        // flag. Leave summary null so the API response stays language-neutral and
        // the FR/ES UIs do not pick up a hard-coded English string.
        results.forEach(r -> events.add(TimelineEventDTO.builder()
            .id(r.getId())
            .section(Section.RESULT)
            .occurredAt(r.getResultDate())
            .title(joinNonBlank(" ", r.getTestName(), formatResult(r)))
            .status(r.getAbnormalFlag())
            // QA D1016/D931 (round 18): the chronology carried the value but
            // dropped the result's correction flag, so a corrected value read
            // as the original there while every other section said "Corrigé".
            .corrected(r.isCorrected())
            .build()));

        medications.forEach(m -> events.add(TimelineEventDTO.builder()
            .id(m.getId())
            .section(Section.MEDICATION)
            .occurredAt(m.getCreatedAt())
            .title(joinNonBlank(" — ", m.getMedicationName(), m.getDosage()))
            // QA D1025: the route in the reader's language, never "INTRAVENOUS".
            .summary(joinNonBlank(", ", m.getFrequency(),
                com.example.hms.service.emar.MedicationRoutes.label(m.getRoute()), m.getDuration()))
            .status(m.getStatus())
            .build()));

        imaging.forEach(i -> events.add(TimelineEventDTO.builder()
            .id(i.getId())
            .section(Section.IMAGING)
            .occurredAt(i.getOrderedAt())
            .title(joinNonBlank(" — ", i.getModality(), i.getStudyType()))
            .summary(i.getReportImpression() != null ? i.getReportImpression() : i.getClinicalQuestion())
            .status(i.getReportStatus() != null ? i.getReportStatus() : i.getStatus())
            .build()));

        procedures.forEach(p -> events.add(TimelineEventDTO.builder()
            .id(p.getId())
            .section(Section.PROCEDURE)
            .occurredAt(p.getOrderedAt())
            .title(joinNonBlank(" — ", p.getProcedureName(), p.getProcedureCategory()))
            .summary(p.getIndication())
            .status(p.getStatus())
            .build()));

        // QA D1380: a total order (time, then section, then id), so a timeline
        // page boundary is the same on every read.
        events.sort(Comparator
            .comparing(TimelineEventDTO::getOccurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(TimelineEventDTO::getSection, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TimelineEventDTO::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    /* ------------- entity → DTO mappers ------------------------------ */

    private EncounterEntryDTO toEncounterDto(Encounter e) {
        return EncounterEntryDTO.builder()
            .id(e.getId())
            .code(e.getCode())
            .encounterType(e.getEncounterType() != null ? e.getEncounterType().name() : null)
            .status(e.getStatus() != null ? e.getStatus().name() : null)
            .encounterDate(e.getEncounterDate())
            .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
            .staffFullName(e.getStaff() != null ? e.getStaff().getFullName() : null)
            .chiefComplaint(e.getChiefComplaint())
            .roomAssignment(e.getRoomAssignment())
            .build();
    }

    private NoteEntryDTO toNoteDto(EncounterNote note, EncounterEntryDTO encounter) {
        return NoteEntryDTO.builder()
            .id(note.getId())
            .encounterId(encounter.getId())
            .encounterCode(encounter.getCode())
            .template(note.getTemplate() != null ? note.getTemplate().name() : null)
            .authorName(note.getAuthorName())
            .authorCredentials(com.example.hms.utility.JobTitleCredentials.toCode(note.getAuthorCredentials()))
            .documentedAt(note.getDocumentedAt())
            .signedAt(note.getSignedAt())
            .signed(note.getSignedAt() != null)
            .lateEntry(note.isLateEntry())
            .requiresCosign(note.isRequiresCosign())
            .cosignedAt(note.getCosignedAt())
            .preview(buildNotePreview(note))
            .source(NOTE_SOURCE_ENCOUNTER)
            .build();
    }

    /**
     * QA D520: the chart review read only the analyzer's stored flag, which a
     * hand-entered result never carries, so a critical potassium showed no
     * CRITIQUE pill here while the lab screen and the doctor's queue (QA D178)
     * graded it from the reference range. Same grade, same three values.
     */
    private String gradedSeverity(LabResult r) {
        String graded = labResultMapper.severityFlagOf(r);
        if (com.example.hms.mapper.LabResultMapper.FLAG_CRITICAL.equals(graded)) {
            return com.example.hms.enums.AbnormalFlag.CRITICAL.name();
        }
        if ("LOW".equals(graded) || "HIGH".equals(graded)
                || com.example.hms.mapper.LabResultMapper.FLAG_ABNORMAL.equals(graded)) {
            return com.example.hms.enums.AbnormalFlag.ABNORMAL.name();
        }
        if ("NORMAL".equals(graded)) {
            return com.example.hms.enums.AbnormalFlag.NORMAL.name();
        }
        // QA D1394: a value the hospital's ranges could not grade says so, whatever an analyser's NORMAL says.
        if (labResultMapper.isUngraded(r)) {
            return com.example.hms.mapper.LabResultMapper.FLAG_UNSPECIFIED;
        }
        return r.getAbnormalFlag() != null ? r.getAbnormalFlag().severity().name() : null;
    }

    private com.example.hms.enums.AbnormalDirection gradedDirection(LabResult r) {
        String graded = labResultMapper.severityFlagOf(r);
        if ("LOW".equals(graded) || "HIGH".equals(graded)) {
            return com.example.hms.enums.AbnormalDirection.valueOf(graded);
        }
        return r.getAbnormalFlag() != null ? r.getAbnormalFlag().direction() : null;
    }

    private ResultEntryDTO toResultDto(LabResult r) {
        LabOrder order = r.getLabOrder();
        String testName = null;
        String testCode = null;
        String orderingStaffName = null;
        if (order != null) {
            if (order.getLabTestDefinition() != null) {
                testName = order.getLabTestDefinition().getName();
                testCode = order.getLabTestDefinition().getTestCode();
            } else {
                // QA D648: the same "deleted test" label as every other lab screen.
                testName = com.example.hms.utility.MessageUtil.resolveOrRaw(
                    com.example.hms.mapper.LabOrderMapper.DELETED_TEST_LABEL);
            }
            Staff os = order.getOrderingStaff();
            if (os != null) {
                orderingStaffName = os.getFullName();
            }
        }
        // QA D740: an unreleased value is not a result yet. The row is listed
        // as pending; its value, unit and flag never leave the laboratory, so
        // the PDF built from this DTO cannot print them either.
        boolean showsValue = ClinicalLabResultView.showsValue(r);
        return ResultEntryDTO.builder()
            .id(r.getId())
            .labOrderId(order != null ? order.getId() : null)
            .testName(testName)
            .testCode(testCode)
            .resultValue(showsValue ? r.getResultValue() : null)
            .resultUnit(showsValue ? com.example.hms.mapper.LabResultMapper.unitOf(r) : null)
            .abnormalFlag(showsValue ? gradedSeverity(r) : null)
            .abnormalDirection(showsValue ? gradedDirection(r) : null)
            .resultDate(r.getResultDate())
            .orderingStaffName(orderingStaffName)
            .acknowledged(r.isAcknowledged())
            .released(r.isReleased())
            // QA D1016: the chart review says the value replaces a released one.
            .corrected(showsValue && com.example.hms.service.lab.LabResultCorrections.isCorrection(r))
            .build();
    }


    /**
     * QA D678: prescriber tick OR hospital catalog item controlled. Optional so
     * a hand-built instance in unit tests still works (then the tick alone).
     */
    private com.example.hms.service.pharmacy.PrescriptionControlledResolver controlledResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setControlledResolver(com.example.hms.service.pharmacy.PrescriptionControlledResolver controlledResolver) {
        this.controlledResolver = controlledResolver;
    }

    private boolean controlled(com.example.hms.model.Prescription p) {
        return controlledResolver != null ? controlledResolver.isControlled(p) : p.isControlledSubstance();
    }

    private MedicationEntryDTO toMedicationDto(Prescription p) {
        Staff prescriber = p.getStaff();
        return MedicationEntryDTO.builder()
            .id(p.getId())
            .medicationName(p.getMedicationDisplayName() != null
                ? p.getMedicationDisplayName() : p.getMedicationName())
            .medicationCode(p.getMedicationCode())
            .dosage(joinNonBlank(" ", p.getDosage(), p.getDoseUnit()))
            .route(p.getRoute())
            .frequency(p.getFrequency())
            .duration(p.getDuration())
            .status(p.getStatus() != null ? p.getStatus().name() : null)
            .createdAt(p.getCreatedAt())
            .prescriberName(prescriber != null ? prescriber.getFullName() : null)
            .controlledSubstance(controlled(p))
            .inpatientOrder(p.isInpatientOrder())
            .build();
    }

    private ImagingEntryDTO toImagingDto(ImagingOrder o, ImagingReport latest) {
        LocalDateTime scheduledFor = o.getScheduledDate() != null
            ? o.getScheduledDate().atStartOfDay() : null;
        return ImagingEntryDTO.builder()
            .id(o.getId())
            .modality(o.getModality() != null ? o.getModality().name() : null)
            .studyType(o.getStudyType())
            .bodyRegion(o.getBodyRegion())
            .laterality(o.getLaterality() != null ? o.getLaterality().name() : null)
            .priority(o.getPriority() != null ? o.getPriority().name() : null)
            .status(o.getStatus() != null ? o.getStatus().name() : null)
            .orderedAt(o.getOrderedAt())
            .scheduledFor(scheduledFor)
            .clinicalQuestion(o.getClinicalQuestion())
            .reportStatus(latest != null && latest.getReportStatus() != null
                ? latest.getReportStatus().name() : null)
            .reportImpression(latest != null ? truncatePreview(latest.getImpression()) : null)
            .build();
    }

    private ProcedureEntryDTO toProcedureDto(ProcedureOrder p) {
        return ProcedureEntryDTO.builder()
            .id(p.getId())
            .procedureName(p.getProcedureName())
            .procedureCode(p.getProcedureCode())
            .procedureCategory(p.getProcedureCategory())
            .urgency(p.getUrgency() != null ? p.getUrgency().name() : null)
            .status(p.getStatus() != null ? p.getStatus().name() : null)
            .orderedAt(p.getOrderedAt())
            .scheduledFor(p.getScheduledDatetime())
            .orderingProviderName(p.getOrderingProvider() != null
                ? p.getOrderingProvider().getFullName() : null)
            .indication(p.getIndication())
            .consentObtained(Boolean.TRUE.equals(p.getConsentObtained()))
            .build();
    }

    /* ------------- helpers ------------------------------------------- */

    private String resolveHospitalName(UUID hospitalId) {
        if (hospitalId == null) {
            return null;
        }
        return hospitalRepository.findClinicalById(hospitalId)
            .map(Hospital::getName)
            .orElse(null);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(limit, MIN_LIMIT, MAX_LIMIT);
    }

    private static String buildNotePreview(EncounterNote note) {
        // Pick the first non-blank body field in roughly the order a clinician
        // would scan the note: assessment > plan > summary > HPI > subjective > objective.
        String[] candidates = {
            note.getAssessment(),
            note.getPlan(),
            note.getSummary(),
            note.getHistoryOfPresentIllness(),
            note.getSubjective(),
            note.getObjective(),
            note.getChiefComplaint(),
        };
        for (String c : candidates) {
            String preview = truncatePreview(c);
            if (preview != null) {
                return preview;
            }
        }
        return null;
    }

    private static String truncatePreview(String text) {
        if (text == null) return null;
        String stripped = text.strip();
        if (stripped.isEmpty()) return null;
        if (stripped.length() <= PREVIEW_LENGTH) return stripped;
        return stripped.substring(0, PREVIEW_LENGTH) + "…";
    }

    private static String formatResult(ResultEntryDTO r) {
        if (r.getResultValue() == null) return null;
        // QA D1106 (D1002 class): the chronology title in the reader's number format.
        return com.example.hms.utility.DisplayNumbers.labValueWithUnit(r.getResultValue(), r.getResultUnit(),
            com.example.hms.utility.DisplayNumbers.requestLocale());
    }

    private static String joinNonBlank(String sep, String... parts) {
        if (parts == null || parts.length == 0) return null;
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (!out.isEmpty()) out.append(sep);
            out.append(part);
        }
        return out.isEmpty() ? null : out.toString();
    }
}
