package com.example.hms.service.timeline;

import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.GeneralReferral;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Staff;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.payload.dto.PatientTimelineEntryDTO;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D959: the doctor's chronology showed only encounters, prescriptions,
 * lab results and allergies. After a full consultation the diagnoses, the lab
 * and imaging orders still waiting, the signed note with its plan and the
 * referrals were missing, so the timeline did not tell the visit.
 *
 * <p>These rows are the acting hospital's own. Cross-hospital widening of
 * these categories needs the same sensitivity accounting the shared ones have
 * (E8 #49/#51) and is tracked as standing debt; until then another hospital's
 * rows are not read here at all.
 */
@Component
@RequiredArgsConstructor
public class VisitTimelineRows {

    public static final String CATEGORY_DIAGNOSIS = "DIAGNOSIS";
    public static final String CATEGORY_LAB_ORDER = "LAB_ORDER";
    public static final String CATEGORY_IMAGING = "IMAGING";
    public static final String CATEGORY_NOTE = "NOTE";
    public static final String CATEGORY_REFERRAL = "REFERRAL";

    private static final String META_STATUS = "status";
    private static final String META_CLINICIAN = "clinician";

    /** A lab order with one of these has its result row instead, or is void. */
    private static final Set<LabOrderStatus> LAB_ORDER_CLOSED = EnumSet.of(
        LabOrderStatus.RESULTED, LabOrderStatus.VERIFIED, LabOrderStatus.COMPLETED,
        LabOrderStatus.CANCELLED, LabOrderStatus.ENTERED_IN_ERROR);

    private final PatientProblemRepository problemRepository;
    private final LabOrderRepository labOrderRepository;
    private final ImagingOrderRepository imagingOrderRepository;
    private final GeneralReferralRepository referralRepository;
    private final EncounterRepository encounterRepository;
    private final EncounterNoteRepository encounterNoteRepository;
    /** The same classification the other chronology rows use (E8 #51): explicit tag, codes, department. */
    private final com.example.hms.service.recordaccess.SensitivityClassifier sensitivityClassifier;

    /** A referral to one of these is sensitive whatever department receives it. */
    private static final Set<com.example.hms.enums.ReferralSpecialty> SENSITIVE_SPECIALTIES = EnumSet.of(
        com.example.hms.enums.ReferralSpecialty.PSYCHIATRY, com.example.hms.enums.ReferralSpecialty.PSYCHOLOGY,
        com.example.hms.enums.ReferralSpecialty.BEHAVIORAL_HEALTH, com.example.hms.enums.ReferralSpecialty.INFECTIOUS_DISEASE);

    /** Every visit row of the patient at the acting hospital, in the categories asked for. */
    public List<PatientTimelineEntryDTO> collect(UUID patientId, UUID actingHospitalId, Set<String> categoryFilters) {
        List<PatientTimelineEntryDTO> rows = new ArrayList<>();
        // The patient's visits here, read once: a note, an order and an imaging
        // order take their sensitivity from the visit that wrote them.
        Map<UUID, Encounter> visitsHere = new HashMap<>();
        if (wanted(categoryFilters, CATEGORY_NOTE) || wanted(categoryFilters, CATEGORY_IMAGING)) {
            encounterRepository.findByPatient_Id(patientId).stream()
                .filter(encounter -> !encounter.isPrescriptionAnchor())
                .filter(encounter -> isLocal(encounter.getHospital(), actingHospitalId))
                .filter(encounter -> encounter.getId() != null)
                .forEach(encounter -> visitsHere.put(encounter.getId(), encounter));
        }
        if (wanted(categoryFilters, CATEGORY_DIAGNOSIS)) {
            problemRepository.findByPatient_IdAndHospital_Id(patientId, actingHospitalId)
                .forEach(problem -> rows.add(diagnosisRow(problem, actingHospitalId)));
        }
        if (wanted(categoryFilters, CATEGORY_LAB_ORDER)) {
            labOrderRepository.findByPatient_IdAndHospital_Id(patientId, actingHospitalId).stream()
                .filter(order -> order.getStatus() == null || !LAB_ORDER_CLOSED.contains(order.getStatus()))
                .forEach(order -> rows.add(labOrderRow(order, actingHospitalId)));
        }
        if (wanted(categoryFilters, CATEGORY_IMAGING)) {
            imagingOrderRepository.findByPatient_IdAndHospital_Id(patientId, actingHospitalId).stream()
                .filter(order -> order.getStatus() != ImagingOrderStatus.DRAFT)
                .forEach(order -> rows.add(imagingOrderRow(order, actingHospitalId,
                    order.getEncounterId() != null ? visitsHere.get(order.getEncounterId()) : null)));
        }
        if (wanted(categoryFilters, CATEGORY_NOTE)) {
            List<UUID> visits = new ArrayList<>(visitsHere.keySet());
            if (!visits.isEmpty()) {
                encounterNoteRepository.findByEncounter_IdIn(visits).stream()
                    .filter(note -> note.getSignedAt() != null)
                    .forEach(note -> rows.add(noteRow(note, actingHospitalId)));
            }
        }
        if (wanted(categoryFilters, CATEGORY_REFERRAL)) {
            referralRepository.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patientId, actingHospitalId).stream()
                .filter(referral -> referral.getStatus() != ReferralStatus.DRAFT)
                .forEach(referral -> rows.add(referralRow(referral, actingHospitalId)));
        }
        return rows;
    }

    private PatientTimelineEntryDTO diagnosisRow(PatientProblem problem, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, problem.getStatus() != null ? problem.getStatus().name() : null);
        putIfNotNull(metadata, "code", problem.getProblemCode());
        putIfNotNull(metadata, META_CLINICIAN, staffName(problem.getRecordedBy()));
        String what = joinCodeAndName(problem.getProblemCode(), problem.getProblemDisplay());
        String summary = MessageUtil.resolveOrRaw("timeline.diagnosis.summary",
            what != null ? what : MessageUtil.resolveOrRaw("timeline.category.DIAGNOSIS"),
            statusLabel(problem.getStatus() != null ? problem.getStatus().name() : null));
        return row(problem.getId(), CATEGORY_DIAGNOSIS,
            coalesce(problem.getCreatedAt(), atStartOfDay(problem.getOnsetDate())), summary,
            sensitivityClassifier.effectiveCategory(problem) != null, metadata, problem.getHospital(), acting);
    }

    private PatientTimelineEntryDTO labOrderRow(LabOrder order, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, order.getStatus() != null ? order.getStatus().name() : null);
        putIfNotNull(metadata, "priority", order.getPriority());
        putIfNotNull(metadata, META_CLINICIAN, staffName(order.getOrderingStaff()));
        String test = order.getLabTestDefinition() != null
            ? firstNonBlank(order.getLabTestDefinition().getName(), order.getLabTestDefinition().getTestCode())
            : null;
        String summary = MessageUtil.resolveOrRaw("timeline.labOrder.summary",
            test != null ? test : MessageUtil.resolveOrRaw("timeline.category.LAB_ORDER"));
        return row(order.getId(), CATEGORY_LAB_ORDER, order.getOrderDatetime(), summary, sensitive(order.getEncounter()),
            metadata, order.getHospital(), acting);
    }

    private PatientTimelineEntryDTO imagingOrderRow(ImagingOrder order, UUID acting, Encounter visit) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, order.getStatus() != null ? order.getStatus().name() : null);
        putIfNotNull(metadata, META_CLINICIAN, order.getOrderingProviderName());
        String modality = order.getModality() != null ? label(order.getModality()) : null;
        String study = firstNonBlank(order.getStudyType(), order.getBodyRegion());
        String what = modality != null && study != null ? modality + " · " + study : firstNonBlank(modality, study);
        String summary = MessageUtil.resolveOrRaw("timeline.imagingOrder.summary",
            what != null ? what : MessageUtil.resolveOrRaw("timeline.category.IMAGING"),
            order.getStatus() != null ? label(order.getStatus()) : "");
        return row(order.getId(), CATEGORY_IMAGING, order.getOrderedAt(), summary, sensitive(visit), metadata,
            order.getHospital(), acting);
    }

    private PatientTimelineEntryDTO noteRow(EncounterNote note, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_CLINICIAN, firstNonBlank(note.getSignedByName(), note.getAuthorName()));
        String assessment = truncate(firstNonBlank(note.getAssessment(), note.getSummary(), note.getChiefComplaint()));
        String plan = truncate(note.getPlan());
        StringBuilder summary = new StringBuilder(MessageUtil.resolveOrRaw("timeline.note.signed"));
        if (assessment != null) {
            summary.append(" — ").append(assessment);
        }
        if (plan != null) {
            summary.append(" · ").append(MessageUtil.resolveOrRaw("timeline.note.plan", plan));
        }
        return row(note.getId(), CATEGORY_NOTE, note.getSignedAt(), summary.toString(), sensitive(note.getEncounter()),
            metadata, note.getHospital(), acting);
    }

    private PatientTimelineEntryDTO referralRow(GeneralReferral referral, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, referral.getStatus() != null ? referral.getStatus().name() : null);
        putIfNotNull(metadata, "urgency", referral.getUrgency() != null ? referral.getUrgency().name() : null);
        putIfNotNull(metadata, META_CLINICIAN, staffName(referral.getReferringProvider()));
        String target = firstNonBlank(
            referral.getTargetDepartment() != null ? referral.getTargetDepartment().getName() : null,
            referral.getTargetFacilityName(),
            referral.getTargetSpecialty() != null ? label(referral.getTargetSpecialty()) : null);
        String summary = MessageUtil.resolveOrRaw("timeline.referral.summary",
            target != null ? target : "—",
            referral.getStatus() != null ? label(referral.getStatus()) : "");
        String reason = truncate(firstNonBlank(referral.getReferralReason(), referral.getClinicalIndication()));
        if (reason != null) {
            summary = summary + " — " + reason;
        }
        boolean sensitive = (referral.getTargetSpecialty() != null && SENSITIVE_SPECIALTIES.contains(referral.getTargetSpecialty()))
            || sensitivityClassifier.effectiveCategory(null, referral.getTargetDepartment()) != null
            || sensitivityClassifier.effectiveCategory(null, referral.getSourceDepartment()) != null;
        return row(referral.getId(), CATEGORY_REFERRAL,
            coalesce(referral.getSubmittedAt(), referral.getCreatedAt()), summary, sensitive, metadata,
            referral.getHospital(), acting);
    }

    /** A row written in a sensitive visit is sensitive (the timeline hides it unless asked). */
    private boolean sensitive(Encounter visit) {
        return visit != null && sensitivityClassifier.effectiveCategory(visit) != null;
    }

    private static PatientTimelineEntryDTO row(UUID id, String category, LocalDateTime when, String summary,
                                               boolean sensitive, Map<String, Object> metadata,
                                               Hospital hospital, UUID acting) {
        if (hospital != null && hospital.getId() != null) {
            metadata.put("sourceHospitalId", hospital.getId().toString());
            putIfNotNull(metadata, "sourceHospitalName", hospital.getName());
            metadata.put("foreign", !hospital.getId().equals(acting));
        }
        return PatientTimelineEntryDTO.builder()
            .entryId(id != null ? id.toString() : null)
            .category(category)
            .occurredAt(when)
            .summary(summary)
            .sensitive(sensitive)
            .metadata(metadata)
            .build();
    }

    /**
     * An enum constant in the reader's language: the chronology's own labels,
     * then the record labels the chart PDF already carries (XRAY
     * "Radiographie", INPATIENT "Hospitalisation"…), else the constant made
     * readable. A guard keeps every constant the chronology prints labelled.
     */
    public static String label(Enum<?> value) {
        String label = MessageUtil.resolveOrNull(null, "timeline.enum." + value.name());
        if (label == null) {
            label = MessageUtil.resolveOrNull(null, "pdf.record.enum." + value.name());
        }
        if (label != null) {
            return label;
        }
        String words = value.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String statusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        String label = MessageUtil.resolveOrNull(null, "pdf.record.enum." + status.trim().toUpperCase(Locale.ROOT));
        return label != null ? label : status;
    }

    private static boolean wanted(Set<String> filters, String category) {
        return filters == null || filters.isEmpty() || filters.contains(category);
    }

    private static boolean isLocal(Hospital hospital, UUID acting) {
        return hospital != null && hospital.getId() != null && hospital.getId().equals(acting);
    }

    private static String staffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        return firstNonBlank(staff.getFullName(), staff.getName());
    }

    private static String joinCodeAndName(String code, String name) {
        String c = blankToNull(code);
        String n = blankToNull(name);
        if (c != null && n != null && !n.startsWith(c)) {
            return c + " — " + n;
        }
        return n != null ? n : c;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String v = blankToNull(value);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        return v.length() > 160 ? v.substring(0, 157) + "…" : v;
    }

    private static LocalDateTime coalesce(LocalDateTime first, LocalDateTime second) {
        return first != null ? first : second;
    }

    private static LocalDateTime atStartOfDay(java.time.LocalDate day) {
        return Optional.ofNullable(day).map(java.time.LocalDate::atStartOfDay).orElse(null);
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
