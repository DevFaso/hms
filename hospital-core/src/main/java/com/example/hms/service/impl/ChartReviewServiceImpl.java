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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        List<Encounter> source = hospitalId != null
            ? encounterRepository.findAllByPatient_IdAndHospital_Id(patientId, hospitalId)
            : encounterRepository.findByPatient_Id(patientId);
        return source.stream()
            .sorted(Comparator
                .comparing(Encounter::getEncounterDate, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(limit)
            .map(this::toEncounterDto)
            .toList();
    }

    /**
     * Notes are aligned to the encounters we just loaded (one note per encounter
     * via {@code uk_encounter_note_encounter}), so we avoid a second patient-wide
     * scan and keep the payload coherent.
     */
    private List<NoteEntryDTO> loadNotes(List<EncounterEntryDTO> encounters) {
        if (encounters == null || encounters.isEmpty()) {
            return List.of();
        }
        List<NoteEntryDTO> notes = new ArrayList<>();
        for (EncounterEntryDTO enc : encounters) {
            if (enc.getId() == null) continue;
            Optional<EncounterNote> note = encounterNoteRepository.findByEncounter_Id(enc.getId());
            note.map(n -> toNoteDto(n, enc)).ifPresent(notes::add);
        }
        notes.sort(Comparator.comparing(NoteEntryDTO::getDocumentedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return notes;
    }

    private List<ResultEntryDTO> loadResults(UUID patientId, UUID hospitalId, int limit) {
        List<LabResult> source;
        if (hospitalId != null) {
            source = labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
                patientId, hospitalId, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "resultDate")));
        } else {
            source = labResultRepository.findByLabOrder_Patient_Id(patientId);
        }
        return source.stream()
            .sorted(Comparator.comparing(LabResult::getResultDate,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(limit)
            .map(this::toResultDto)
            .toList();
    }

    private List<MedicationEntryDTO> loadMedications(UUID patientId, UUID hospitalId, int limit) {
        List<Prescription> source;
        if (hospitalId != null) {
            source = prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId);
        } else {
            source = prescriptionRepository
                .findByPatient_Id(patientId, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
        }
        return source.stream()
            .sorted(Comparator.comparing(Prescription::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(limit)
            .map(this::toMedicationDto)
            .toList();
    }

    private List<ImagingEntryDTO> loadImaging(UUID patientId, UUID hospitalId, int limit) {
        List<ImagingOrder> orders = imagingOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId);
        return orders.stream()
            .filter(o -> hospitalId == null
                || (o.getHospital() != null
                    && Objects.equals(o.getHospital().getId(), hospitalId)))
            .limit(limit)
            .map(this::toImagingDto)
            .toList();
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

        results.forEach(r -> events.add(TimelineEventDTO.builder()
            .id(r.getId())
            .section(Section.RESULT)
            .occurredAt(r.getResultDate())
            .title(joinNonBlank(" ", r.getTestName(), formatResult(r)))
            .summary(r.getAbnormalFlag() != null ? "Abnormal flag: " + r.getAbnormalFlag() : null)
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
        return events.stream().limit((long) limit).toList();
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

    private ImagingEntryDTO toImagingDto(ImagingOrder o) {
        ImagingReport latest = imagingReportRepository
            .findFirstByImagingOrder_IdAndLatestVersionIsTrue(o.getId())
            .or(() -> imagingReportRepository.findTopByImagingOrder_IdOrderByReportVersionDesc(o.getId()))
            .orElse(null);

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
        return Math.min(MAX_LIMIT, Math.max(MIN_LIMIT, limit));
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
            if (out.length() > 0) out.append(sep);
            out.append(part);
        }
        return out.length() == 0 ? null : out.toString();
    }
}
