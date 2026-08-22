package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
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
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.service.ChartReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates the six clinical sections shown in the Chart Review viewer
 * (Encounters / Notes / Results / Medications / Imaging / Procedures)
 * plus a unified timeline. Queries existing repositories only — no
 * schema changes — and caps per-section results so the payload stays
 * small on metered links.
 */
@Service
@RequiredArgsConstructor
public class ChartReviewServiceImpl implements ChartReviewService {

    /** Length used for note / imaging-impression preview snippets. */
    static final int PREVIEW_LENGTH = 280;

    private final PatientRepository patientRepository;
    private final EncounterRepository encounterRepository;
    private final EncounterNoteRepository encounterNoteRepository;
    private final LabResultRepository labResultRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final ImagingOrderRepository imagingOrderRepository;
    private final ImagingReportRepository imagingReportRepository;
    private final ProcedureOrderRepository procedureOrderRepository;
    private final HospitalRepository hospitalRepository;

    @Override
    @Transactional(readOnly = true)
    public ChartReviewDTO getChartReview(UUID patientId, UUID hospitalId, Integer limit) {
        if (patientId == null) {
            throw new ResourceNotFoundException("patient.notFound", "<null>");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));

        int effectiveLimit = clampLimit(limit);

        List<EncounterEntryDTO> encounters = loadEncounters(patient.getId(), hospitalId, effectiveLimit);
        List<NoteEntryDTO> notes = loadNotes(encounters);
        List<ResultEntryDTO> results = loadResults(patient.getId(), hospitalId, effectiveLimit);
        List<MedicationEntryDTO> medications = loadMedications(patient.getId(), hospitalId, effectiveLimit);
        List<ImagingEntryDTO> imaging = loadImaging(patient.getId(), hospitalId, effectiveLimit);
        List<ProcedureEntryDTO> procedures = loadProcedures(patient.getId(), hospitalId, effectiveLimit);

        List<TimelineEventDTO> timeline = buildTimeline(
            encounters, notes, results, medications, imaging, procedures, effectiveLimit);

        return ChartReviewDTO.builder()
            .patientId(patient.getId())
            .hospitalId(hospitalId)
            .hospitalName(resolveHospitalName(hospitalId))
            .limit(effectiveLimit)
            .encounters(encounters)
            .notes(notes)
            .results(results)
            .medications(medications)
            .imaging(imaging)
            .procedures(procedures)
            .timeline(timeline)
            .generatedAt(LocalDateTime.now())
            .build();
    }

    /* ------------- per-section loaders ------------------------------- */

    private List<EncounterEntryDTO> loadEncounters(UUID patientId, UUID hospitalId, int limit) {
        Pageable page = PageRequest.of(0, limit);
        List<Encounter> source = hospitalId != null
            ? encounterRepository
                .findByPatient_IdAndHospital_IdOrderByEncounterDateDesc(patientId, hospitalId, page)
                .getContent()
            : encounterRepository
                .findByPatient_IdOrderByEncounterDateDesc(patientId, page)
                .getContent();
        return source.stream()
            .map(this::toEncounterDto)
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
                && encounterById.containsKey(n.getEncounter().getId()))
            .map(n -> toNoteDto(n, encounterById.get(n.getEncounter().getId())))
            .sorted(Comparator.comparing(NoteEntryDTO::getDocumentedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private List<ResultEntryDTO> loadResults(UUID patientId, UUID hospitalId, int limit) {
        Pageable page = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "resultDate"));
        List<LabResult> source = hospitalId != null
            ? labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
                patientId, hospitalId, page)
            : labResultRepository.findByLabOrder_Patient_Id(patientId, page).getContent();
        return source.stream()
            .map(this::toResultDto)
            .toList();
    }

    private List<MedicationEntryDTO> loadMedications(UUID patientId, UUID hospitalId, int limit) {
        Pageable page = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Prescription> source = hospitalId != null
            ? prescriptionRepository
                .findByPatient_IdAndHospital_Id(patientId, hospitalId, page)
                .getContent()
            : prescriptionRepository
                .findByPatient_Id(patientId, page)
                .getContent();
        return source.stream()
            .map(this::toMedicationDto)
            .toList();
    }

    private List<ImagingEntryDTO> loadImaging(UUID patientId, UUID hospitalId, int limit) {
        Pageable page = PageRequest.of(0, limit);
        List<ImagingOrder> orders = hospitalId != null
            ? imagingOrderRepository
                .findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(patientId, hospitalId, page)
                .getContent()
            : imagingOrderRepository
                .findByPatient_IdOrderByOrderedAtDesc(patientId, page)
                .getContent();
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<UUID, ImagingReport> latestByOrderId = batchLoadLatestReports(orders);
        return orders.stream()
            .map(o -> toImagingDto(o, latestByOrderId.get(o.getId())))
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

    private List<ProcedureEntryDTO> loadProcedures(UUID patientId, UUID hospitalId, int limit) {
        List<ProcedureOrder> orders = hospitalId != null
            ? procedureOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(patientId, hospitalId)
            : procedureOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId);
        return orders.stream()
            .limit(limit)
            .map(this::toProcedureDto)
            .toList();
    }

    /* ------------- timeline ------------------------------------------ */

    private List<TimelineEventDTO> buildTimeline(
        List<EncounterEntryDTO> encounters,
        List<NoteEntryDTO> notes,
        List<ResultEntryDTO> results,
        List<MedicationEntryDTO> medications,
        List<ImagingEntryDTO> imaging,
        List<ProcedureEntryDTO> procedures,
        int limit
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
            .title(joinNonBlank(" — ", n.getTemplate(), n.getAuthorName()))
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
            .build()));

        medications.forEach(m -> events.add(TimelineEventDTO.builder()
            .id(m.getId())
            .section(Section.MEDICATION)
            .occurredAt(m.getCreatedAt())
            .title(joinNonBlank(" — ", m.getMedicationName(), m.getDosage()))
            .summary(joinNonBlank(", ", m.getFrequency(), m.getRoute(), m.getDuration()))
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

        events.sort(Comparator
            .comparing(TimelineEventDTO::getOccurredAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return events.stream().limit(limit).toList();
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
            .authorCredentials(note.getAuthorCredentials())
            .documentedAt(note.getDocumentedAt())
            .signedAt(note.getSignedAt())
            .signed(note.getSignedAt() != null)
            .lateEntry(note.isLateEntry())
            .requiresCosign(note.isRequiresCosign())
            .cosignedAt(note.getCosignedAt())
            .preview(buildNotePreview(note))
            .build();
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
            }
            Staff os = order.getOrderingStaff();
            if (os != null) {
                orderingStaffName = os.getFullName();
            }
        }
        return ResultEntryDTO.builder()
            .id(r.getId())
            .labOrderId(order != null ? order.getId() : null)
            .testName(testName)
            .testCode(testCode)
            .resultValue(r.getResultValue())
            .resultUnit(r.getResultUnit())
            .abnormalFlag(r.getAbnormalFlag() != null ? r.getAbnormalFlag().name() : null)
            .resultDate(r.getResultDate())
            .orderingStaffName(orderingStaffName)
            .acknowledged(r.isAcknowledged())
            .released(r.isReleased())
            .build();
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
            .controlledSubstance(p.isControlledSubstance())
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
        return hospitalRepository.findById(hospitalId)
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
        if (r.getResultUnit() == null || r.getResultUnit().isBlank()) return r.getResultValue();
        return r.getResultValue() + " " + r.getResultUnit();
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
