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
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.PatientUploadedDocumentRepository;
import com.example.hms.repository.TreatmentPlanRepository;
import com.example.hms.model.Consultation;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.model.treatment.TreatmentPlan;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.NursingNote;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.referral.ObgynReferral;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import java.util.LinkedHashMap;
import java.util.function.Function;
import com.example.hms.enums.PatientDocumentType;
import org.springframework.data.domain.PageRequest;
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
    /** QA D959 (round 17): the treatment plan, the consultation request, the discharge summary, the certificate. */
    public static final String CATEGORY_TREATMENT_PLAN = "TREATMENT_PLAN";
    public static final String CATEGORY_CONSULTATION = "CONSULTATION";
    public static final String CATEGORY_DISCHARGE_SUMMARY = "DISCHARGE_SUMMARY";
    public static final String CATEGORY_DOCUMENT = "DOCUMENT";
    /** QA D959 (round 18): the rest of the clinical events the registry hunt found. */
    public static final String CATEGORY_TRANSFUSION = "TRANSFUSION";
    public static final String CATEGORY_PROCEDURE_ORDER = "PROCEDURE_ORDER";
    public static final String CATEGORY_OBGYN_REFERRAL = "OBGYN_REFERRAL";
    public static final String CATEGORY_NURSING_NOTE = "NURSING_NOTE";
    public static final String CATEGORY_IMMUNIZATION = "IMMUNIZATION";
    public static final String CATEGORY_DELIVERY = "DELIVERY";
    public static final String CATEGORY_DEATH = "DEATH";
    /** A microbiology culture writes no LabResult: its released report is its own row. */
    public static final String CATEGORY_MICRO_CULTURE = "MICRO_CULTURE";
    private static final String IMMUNIZATION_VOID = "ENTERED_IN_ERROR";
    /** The most rows of one of these kinds the chronology reads at once. */
    private static final int DOCUMENT_ROWS_MAX = 200;

    private static final String META_STATUS = "status";
    private static final String META_URGENCY = "urgency";
    private static final String CREATED_AT = "createdAt";
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
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final ConsultationRepository consultationRepository;
    private final DischargeSummaryRepository dischargeSummaryRepository;
    private final PatientUploadedDocumentRepository documentRepository;
    private final com.example.hms.repository.TransfusionRequestRepository transfusionRequestRepository;
    private final com.example.hms.repository.ProcedureOrderRepository procedureOrderRepository;
    private final com.example.hms.repository.ObgynReferralRepository obgynReferralRepository;
    private final com.example.hms.repository.NursingNoteRepository nursingNoteRepository;
    private final com.example.hms.repository.ImmunizationRepository immunizationRepository;
    private final com.example.hms.repository.DeliveryRecordRepository deliveryRecordRepository;
    private final com.example.hms.repository.DeathRecordRepository deathRecordRepository;
    private final com.example.hms.repository.MicroCultureResultRepository microCultureResultRepository;
    /** The same classification the other chronology rows use (E8 #51): explicit tag, codes, department. */
    private final com.example.hms.service.recordaccess.SensitivityClassifier sensitivityClassifier;

    /** A referral to one of these is sensitive whatever department receives it. */
    private static final Set<com.example.hms.enums.ReferralSpecialty> SENSITIVE_SPECIALTIES = EnumSet.of(
        com.example.hms.enums.ReferralSpecialty.PSYCHIATRY, com.example.hms.enums.ReferralSpecialty.PSYCHOLOGY,
        com.example.hms.enums.ReferralSpecialty.BEHAVIORAL_HEALTH, com.example.hms.enums.ReferralSpecialty.INFECTIOUS_DISEASE);

    /** Every visit row of the patient at the acting hospital, in the categories asked for. */
    public List<PatientTimelineEntryDTO> collect(UUID patientId, UUID actingHospitalId, Set<String> categoryFilters) {
        // The patient's visits here, read once: a note, an order and an imaging
        // order take their sensitivity from the visit that wrote them.
        Map<UUID, Encounter> visitsHere = new HashMap<>();
        if (wanted(categoryFilters, CATEGORY_NOTE) || wanted(categoryFilters, CATEGORY_IMAGING)) {
            encounterRepository.findByPatient_IdAndHospital_Id(patientId, actingHospitalId).stream()
                .filter(encounter -> !encounter.isPrescriptionAnchor())
                .filter(encounter -> encounter.getId() != null)
                .forEach(encounter -> visitsHere.put(encounter.getId(), encounter));
        }
        Scope scope = new Scope(patientId, actingHospitalId, visitsHere);
        // QA D959 (round 18): the chronology is built from one registry of row
        // sources, one per category. TimelineEventRegistryGuardTest holds every
        // patient-owned entity to a category (or a written reason it is not a
        // chronology event), and every category to a portal label and icon.
        List<PatientTimelineEntryDTO> rows = new ArrayList<>();
        for (Map.Entry<String, Function<Scope, List<PatientTimelineEntryDTO>>> source : sources().entrySet()) {
            if (wanted(categoryFilters, source.getKey())) {
                rows.addAll(source.getValue().apply(scope));
            }
        }
        return rows;
    }

    /** What every row source reads: the patient, the acting hospital, the patient's visits here. */
    record Scope(UUID patientId, UUID acting, Map<UUID, Encounter> visitsHere) {
    }

    /** The categories this class writes, in the order it reads them (the keys of {@link #sources()}). */
    public static final List<String> CATEGORIES = List.of(
        CATEGORY_DIAGNOSIS, CATEGORY_LAB_ORDER, CATEGORY_IMAGING, CATEGORY_NOTE, CATEGORY_REFERRAL,
        CATEGORY_TREATMENT_PLAN, CATEGORY_CONSULTATION, CATEGORY_DISCHARGE_SUMMARY, CATEGORY_DOCUMENT,
        CATEGORY_TRANSFUSION, CATEGORY_PROCEDURE_ORDER, CATEGORY_OBGYN_REFERRAL, CATEGORY_NURSING_NOTE,
        CATEGORY_IMMUNIZATION, CATEGORY_DELIVERY, CATEGORY_DEATH, CATEGORY_MICRO_CULTURE);

    /** The registry: one row source per category of {@link #CATEGORIES}. */
    Map<String, Function<Scope, List<PatientTimelineEntryDTO>>> sources() {
        Map<String, Function<Scope, List<PatientTimelineEntryDTO>>> sources = new LinkedHashMap<>();
        sources.put(CATEGORY_DIAGNOSIS, this::diagnosisRows);
        sources.put(CATEGORY_LAB_ORDER, this::labOrderRows);
        sources.put(CATEGORY_IMAGING, this::imagingOrderRows);
        sources.put(CATEGORY_NOTE, this::noteRows);
        sources.put(CATEGORY_REFERRAL, this::referralRows);
        sources.put(CATEGORY_TREATMENT_PLAN, this::treatmentPlanRows);
        sources.put(CATEGORY_CONSULTATION, this::consultationRows);
        sources.put(CATEGORY_DISCHARGE_SUMMARY, this::dischargeSummaryRows);
        sources.put(CATEGORY_DOCUMENT, this::certificateRows);
        sources.put(CATEGORY_TRANSFUSION, this::transfusionRows);
        sources.put(CATEGORY_PROCEDURE_ORDER, this::procedureOrderRows);
        sources.put(CATEGORY_OBGYN_REFERRAL, this::obgynReferralRows);
        sources.put(CATEGORY_NURSING_NOTE, this::nursingNoteRows);
        sources.put(CATEGORY_IMMUNIZATION, this::immunizationRows);
        sources.put(CATEGORY_DELIVERY, this::deliveryRows);
        sources.put(CATEGORY_DEATH, this::deathRows);
        sources.put(CATEGORY_MICRO_CULTURE, this::microCultureRows);
        return sources;
    }

    private List<PatientTimelineEntryDTO> diagnosisRows(Scope s) {
        // Every diagnosis, with its status: a removed one reads "(Inactif)", as on
        // the chart's history; a clinician may also set INACTIVE, so it is not hidden.
        return problemRepository.findByPatient_IdAndHospital_Id(s.patientId(), s.acting()).stream()
            .map(problem -> diagnosisRow(problem, s.acting()))
            .toList();
    }

    private List<PatientTimelineEntryDTO> labOrderRows(Scope s) {
        return labOrderRepository.findByPatient_IdAndHospital_Id(s.patientId(), s.acting()).stream()
            .filter(order -> order.getStatus() == null || !LAB_ORDER_CLOSED.contains(order.getStatus()))
            .map(order -> labOrderRow(order, s.acting()))
            .toList();
    }

    private List<PatientTimelineEntryDTO> imagingOrderRows(Scope s) {
        return imagingOrderRepository.findByPatient_IdAndHospital_Id(s.patientId(), s.acting()).stream()
            .filter(order -> order.getStatus() != ImagingOrderStatus.DRAFT
                && order.getStatus() != ImagingOrderStatus.CANCELLED)
            .map(order -> imagingOrderRow(order, s.acting(),
                order.getEncounterId() != null ? s.visitsHere().get(order.getEncounterId()) : null))
            .toList();
    }

    private List<PatientTimelineEntryDTO> noteRows(Scope s) {
        List<UUID> visits = new ArrayList<>(s.visitsHere().keySet());
        if (visits.isEmpty()) {
            return List.of();
        }
        return encounterNoteRepository.findByEncounter_IdIn(visits).stream()
            .filter(note -> note.getSignedAt() != null)
            .map(note -> noteRow(note, s.acting()))
            .toList();
    }

    private List<PatientTimelineEntryDTO> referralRows(Scope s) {
        return referralRepository.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(s.patientId(), s.acting()).stream()
            .filter(referral -> referral.getStatus() != ReferralStatus.DRAFT)
            .map(referral -> referralRow(referral, s.acting()))
            .toList();
    }

    // QA D959 (round 17): the treatment plan, the consultation request, the
    // discharge summary and the medical certificate were still missing.
    private List<PatientTimelineEntryDTO> treatmentPlanRows(Scope s) {
        Page<TreatmentPlan> plans = treatmentPlanRepository
            .findAllByPatientIdAndHospitalId(s.patientId(), s.acting(), newestFirst(CREATED_AT));
        return plans == null ? List.of()
            : plans.getContent().stream().map(plan -> treatmentPlanRow(plan, s.acting())).toList();
    }

    private List<PatientTimelineEntryDTO> consultationRows(Scope s) {
        return consultationRepository.findByPatient_IdAndHospital_IdInOrderByRequestedAtDesc(s.patientId(),
                Set.of(s.acting())).stream()
            .map(consultation -> consultationRow(consultation, s.acting()))
            .toList();
    }

    private List<PatientTimelineEntryDTO> dischargeSummaryRows(Scope s) {
        return dischargeSummaryRepository.findByPatient_IdAndHospital_IdOrderByDischargeDateDesc(s.patientId(), s.acting())
            .stream().map(summary -> dischargeSummaryRow(summary, s.acting())).toList();
    }

    private List<PatientTimelineEntryDTO> certificateRows(Scope s) {
        // A document this hospital issued (a certificate); never the patient's own uploads.
        Page<PatientUploadedDocument> issued = documentRepository.findForStaffByType(
            s.patientId(), s.acting(), PatientDocumentType.MEDICAL_CERTIFICATE, newestFirst(CREATED_AT));
        return issued == null ? List.of()
            : issued.getContent().stream()
                .filter(document -> document.getIssuingHospital() != null)
                .map(document -> certificateRow(document, s.acting()))
                .toList();
    }

    // QA D959 (round 18): transfusion requests, procedure orders and OB/GYN
    // referrals were still missing, and the registry hunt found the ward's
    // nursing notes, the vaccines given, the delivery and the death too.
    private List<PatientTimelineEntryDTO> transfusionRows(Scope s) {
        return transfusionRequestRepository.findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(s.patientId(), s.acting())
            .stream().map(request -> transfusionRow(request, s.acting())).toList();
    }

    private List<PatientTimelineEntryDTO> procedureOrderRows(Scope s) {
        return procedureOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(s.patientId(), s.acting())
            .stream().map(order -> procedureOrderRow(order, s.acting())).toList();
    }

    private List<PatientTimelineEntryDTO> obgynReferralRows(Scope s) {
        Page<ObgynReferral> referrals = obgynReferralRepository
            .findByPatient_IdAndHospital_IdIn(s.patientId(), Set.of(s.acting()), newestFirst(CREATED_AT));
        return referrals == null ? List.of()
            : referrals.getContent().stream().map(referral -> obgynReferralRow(referral, s.acting())).toList();
    }

    private List<PatientTimelineEntryDTO> nursingNoteRows(Scope s) {
        return nursingNoteRepository.findTop50ByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(s.patientId(),
                Set.of(s.acting())).stream()
            .map(note -> nursingNoteRow(note, s.acting()))
            .toList();
    }

    private List<PatientTimelineEntryDTO> immunizationRows(Scope s) {
        return immunizationRepository.findByPatient_IdAndHospital_IdOrderByAdministrationDateDesc(s.patientId(), s.acting())
            .stream()
            // Entered in error is a void row, not a vaccine given or refused.
            .filter(dose -> dose.getStatus() == null || !IMMUNIZATION_VOID.equalsIgnoreCase(dose.getStatus().trim()))
            .map(dose -> immunizationRow(dose, s.acting()))
            .toList();
    }

    private List<PatientTimelineEntryDTO> deliveryRows(Scope s) {
        return deliveryRecordRepository.findByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(s.patientId(), s.acting())
            .stream().map(delivery -> deliveryRow(delivery, s.acting())).toList();
    }

    private List<PatientTimelineEntryDTO> deathRows(Scope s) {
        return deathRecordRepository.findByPatient_Id(s.patientId()).stream()
            .filter(death -> death.getHospital() != null && s.acting().equals(death.getHospital().getId()))
            .map(death -> deathRow(death, s.acting()))
            .toList();
    }

    private List<PatientTimelineEntryDTO> microCultureRows(Scope s) {
        // A preliminary culture is still the laboratory's; the chronology shows the released report.
        return microCultureResultRepository.findForPatient(s.patientId(), s.acting()).stream()
            .filter(culture -> culture.getStatus() == com.example.hms.enums.MicroCultureStatus.FINAL
                || culture.getStatus() == com.example.hms.enums.MicroCultureStatus.CORRECTED)
            .map(culture -> microCultureRow(culture, s.acting()))
            .toList();
    }

    private PatientTimelineEntryDTO microCultureRow(com.example.hms.model.MicroCultureResult culture, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        boolean corrected = com.example.hms.service.lab.LabResultCorrections.isCorrected(culture);
        metadata.put("corrected", corrected);
        String source = firstNonBlank(culture.getSpecimenSource(),
            culture.getLabOrder() != null && culture.getLabOrder().getLabTestDefinition() != null
                ? culture.getLabOrder().getLabTestDefinition().getName() : null);
        String growth = culture.getGrowthResult() != null ? label(culture.getGrowthResult()) : "—";
        StringBuilder summary = new StringBuilder(MessageUtil.resolveOrRaw("timeline.microCulture.summary",
            source != null ? source : "—", growth));
        // QA D1016/D931 class (round 18): a corrected report says so on the chronology too.
        if (corrected) {
            summary.append(" (").append(MessageUtil.resolveOrRaw("lab.result.correctedLabel")).append(")");
        }
        return row(culture.getId(), CATEGORY_MICRO_CULTURE,
            coalesce(culture.getCorrectedAt(), coalesce(culture.getFinalizedAt(), culture.getCreatedAt())),
            summary.toString(),
            culture.getLabOrder() != null && sensitive(culture.getLabOrder().getEncounter()),
            provenance(metadata, culture.getHospital(), acting));
    }

    private static PageRequest newestFirst(String property) {
        return PageRequest.of(0, DOCUMENT_ROWS_MAX, Sort.by(Sort.Direction.DESC, property));
    }

    private PatientTimelineEntryDTO transfusionRow(TransfusionRequest request, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, request.getStatus() != null ? request.getStatus().name() : null);
        putIfNotNull(metadata, META_URGENCY, request.getUrgency() != null ? request.getUrgency().name() : null);
        putIfNotNull(metadata, META_CLINICIAN, staffName(request.getRequestedBy()));
        String product = request.getProductType() != null ? label(request.getProductType()) : "—";
        String summary = MessageUtil.resolveOrRaw("timeline.transfusion.summary", product,
            request.getUnitsRequested() != null ? request.getUnitsRequested() : 1);
        String indication = truncate(request.getIndication());
        if (indication != null) {
            summary = summary + " — " + indication;
        }
        return row(request.getId(), CATEGORY_TRANSFUSION,
            request.getRequestedAt(),
            TimelineStatus.withStatus(summary, request.getStatus()),
            sensitive(request.getEncounter()),
            provenance(metadata, request.getHospital(), acting));
    }

    private PatientTimelineEntryDTO procedureOrderRow(ProcedureOrder order, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, order.getStatus() != null ? order.getStatus().name() : null);
        putIfNotNull(metadata, META_URGENCY, order.getUrgency() != null ? order.getUrgency().name() : null);
        putIfNotNull(metadata, META_CLINICIAN, staffName(order.getOrderingProvider()));
        String what = firstNonBlank(order.getProcedureName(), order.getProcedureCode());
        String summary = MessageUtil.resolveOrRaw("timeline.procedureOrder.summary", what != null ? what : "—");
        String indication = truncate(order.getIndication());
        if (indication != null) {
            summary = summary + " — " + indication;
        }
        return row(order.getId(), CATEGORY_PROCEDURE_ORDER,
            order.getOrderedAt(),
            TimelineStatus.withStatus(summary, order.getStatus()),
            sensitive(order.getEncounter()),
            provenance(metadata, order.getHospital(), acting));
    }

    private PatientTimelineEntryDTO obgynReferralRow(ObgynReferral referral, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, referral.getStatus() != null ? referral.getStatus().name() : null);
        putIfNotNull(metadata, META_URGENCY, referral.getUrgency() != null ? referral.getUrgency().name() : null);
        putIfNotNull(metadata, META_CLINICIAN, userName(referral.getMidwife()));
        String reason = truncate(firstNonBlank(referral.getReferralReason(), referral.getClinicalIndication()));
        String summary = MessageUtil.resolveOrRaw("timeline.obgynReferral.summary", reason != null ? reason : "—");
        return row(referral.getId(), CATEGORY_OBGYN_REFERRAL,
            referral.getCreatedAt(),
            TimelineStatus.withStatus(summary, referral.getStatus()),
            sensitive(referral.getEncounter()),
            provenance(metadata, referral.getHospital(), acting));
    }

    private PatientTimelineEntryDTO nursingNoteRow(NursingNote note, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_CLINICIAN, firstNonBlank(note.getSignedByName(), note.getAuthorName()));
        String template = note.getTemplate() != null ? label(note.getTemplate()) : "—";
        String summary = MessageUtil.resolveOrRaw("timeline.nursingNote.summary", template);
        // QA D1107: the title the nurse gave the note leads its row.
        String title = blankToNull(note.getTitle());
        if (title != null) {
            summary = summary + " — " + title.strip();
        }
        String body = truncate(firstNonBlank(note.getNarrative(), note.getDataAssessment(), note.getActionSummary(),
            note.getResponseSummary(), note.getDataSubjective()));
        if (body != null) {
            summary = summary + " — " + body;
        }
        return row(note.getId(), CATEGORY_NURSING_NOTE,
            coalesce(note.getSignedAt(), note.getDocumentedAt()),
            summary,
            sensitivityClassifier.effectiveCategory(note) != null,
            provenance(metadata, note.getHospital(), acting));
    }

    private PatientTimelineEntryDTO immunizationRow(PatientImmunization dose, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        String status = blankToNull(dose.getStatus());
        putIfNotNull(metadata, META_STATUS, status);
        putIfNotNull(metadata, META_CLINICIAN, staffName(dose.getAdministeredBy()));
        String vaccine = firstNonBlank(dose.getVaccineDisplay(), dose.getVaccineType(), dose.getVaccineCode());
        String summary = MessageUtil.resolveOrRaw("timeline.immunization.summary", vaccine != null ? vaccine : "—");
        if (dose.getDoseNumber() != null) {
            summary = summary + " · " + MessageUtil.resolveOrRaw("timeline.immunization.dose", dose.getDoseNumber());
        }
        // QA D1113: a dose transcribed from a card, another facility's record
        // or the family's word was not given here. The row names its source,
        // and the hospital that typed it in is where it was RECORDED, not
        // where it was given (it read "domain Centre Hospitalier Wend-Panga"
        // for a BCG given years earlier elsewhere).
        String source = transcribedSourceOf(dose);
        if (source != null) {
            summary = summary + " · " + immunizationSourceLabel(source);
        }
        summary = TimelineStatus.withStatusLabel(summary, status != null ? immunizationStatusLabel(status) : null);
        Map<String, Object> provenance = provenance(metadata, dose.getHospital(), acting);
        if (source != null) {
            provenance.put("transcribed", true);
            provenance.put("sourceOfRecord", source);
            Object recordedAt = provenance.remove("sourceHospitalName");
            putIfNotNull(provenance, "recordedAtHospitalName", recordedAt);
        }
        return row(dose.getId(), CATEGORY_IMMUNIZATION,
            atStartOfDay(dose.getAdministrationDate()),
            summary,
            sensitive(dose.getEncounter()),
            provenance);
    }

    /** The dose given here, by the portal's own code for it. */
    static final String SOURCE_GIVEN_HERE = "GIVEN_HERE";

    /**
     * QA D1113: the source of a dose that was NOT given here (VACCINATION_CARD,
     * EXTERNAL_RECORD, PATIENT_REPORT, a registry), trimmed and upper-cased;
     * null for a dose given here or with no source recorded.
     */
    public static String transcribedSourceOf(PatientImmunization dose) {
        String source = dose != null ? blankToNull(dose.getSourceOfRecord()) : null;
        if (source == null) {
            return null;
        }
        String code = source.trim().toUpperCase(Locale.ROOT);
        return SOURCE_GIVEN_HERE.equals(code) ? null : code;
    }

    /** The source in the reader's language; the text as typed when it is not one of the known codes. */
    private static String immunizationSourceLabel(String code) {
        String label = MessageUtil.resolveOrNull(null, "timeline.immunization.source." + code);
        return label != null ? label : code;
    }

    /** An immunization's free-text status in the reader's language (a String column, not an enum). */
    private static String immunizationStatusLabel(String status) {
        String own = MessageUtil.resolveOrNull(null,
            "timeline.status.Immunization." + status.trim().toUpperCase(Locale.ROOT));
        return own != null ? own : statusLabel(status);
    }

    private PatientTimelineEntryDTO deliveryRow(DeliveryRecord delivery, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_CLINICIAN, staffName(delivery.getDeliveredByStaff()));
        String mode = delivery.getDeliveryMode() != null ? label(delivery.getDeliveryMode()) : "—";
        String summary = MessageUtil.resolveOrRaw("timeline.delivery.summary", mode);
        return row(delivery.getId(), CATEGORY_DELIVERY,
            delivery.getBirthDateTime(),
            summary,
            false,
            provenance(metadata, delivery.getHospital(), acting));
    }

    private PatientTimelineEntryDTO deathRow(DeathRecord death, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_CLINICIAN, staffName(death.getCertifiedBy()));
        String cause = truncate(joinCodeAndName(death.getImmediateCauseCode(), death.getImmediateCause()));
        String summary = MessageUtil.resolveOrRaw("timeline.death.summary", cause != null ? cause : "—");
        return row(death.getId(), CATEGORY_DEATH,
            death.getDiedAt(),
            summary,
            false,
            provenance(metadata, death.getHospital(), acting));
    }

    private static String userName(User user) {
        if (user == null) {
            return null;
        }
        String full = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
            + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return full.isEmpty() ? null : full;
    }

    private PatientTimelineEntryDTO treatmentPlanRow(TreatmentPlan plan, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, plan.getStatus() != null ? plan.getStatus().name() : null);
        putIfNotNull(metadata, META_CLINICIAN, staffName(plan.getAuthor()));
        String what = truncate(plan.getProblemStatement());
        String summary = MessageUtil.resolveOrRaw("timeline.treatmentPlan.summary", what != null ? what : "—");
        return row(plan.getId(), CATEGORY_TREATMENT_PLAN,
            coalesce(plan.getSignOffAt(), plan.getCreatedAt()),
            TimelineStatus.withStatus(summary, plan.getStatus()),
            sensitive(plan.getEncounter()),
            provenance(metadata, plan.getHospital(), acting));
    }

    private PatientTimelineEntryDTO consultationRow(Consultation consultation, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, consultation.getStatus() != null ? consultation.getStatus().name() : null);
        putIfNotNull(metadata, META_CLINICIAN, staffName(consultation.getRequestingProvider()));
        String what = firstNonBlank(consultation.getSpecialtyRequested(),
            consultation.getConsultationType() != null ? label(consultation.getConsultationType()) : null);
        String summary = MessageUtil.resolveOrRaw("timeline.consultation.summary", what != null ? what : "—");
        String reason = truncate(firstNonBlank(consultation.getReasonForConsult(), consultation.getClinicalQuestion()));
        if (reason != null) {
            summary = summary + " — " + reason;
        }
        return row(consultation.getId(), CATEGORY_CONSULTATION,
            consultation.getRequestedAt(),
            TimelineStatus.withStatus(summary, consultation.getStatus()),
            sensitivityClassifier.effectiveCategory(consultation) != null,
            provenance(metadata, consultation.getHospital(), acting));
    }

    private PatientTimelineEntryDTO dischargeSummaryRow(DischargeSummary discharge, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        boolean finalized = Boolean.TRUE.equals(discharge.getIsFinalized());
        metadata.put("finalized", finalized);
        putIfNotNull(metadata, META_CLINICIAN, staffName(discharge.getDischargingProvider()));
        String diagnosis = truncate(discharge.getDischargeDiagnosis());
        String summary = MessageUtil.resolveOrRaw(finalized ? "timeline.dischargeSummary.finalized"
            : "timeline.dischargeSummary.draft", diagnosis != null ? diagnosis : "—");
        return row(discharge.getId(), CATEGORY_DISCHARGE_SUMMARY,
            coalesce(discharge.getFinalizedAt(), coalesce(discharge.getDischargeTime(), discharge.getCreatedAt())),
            summary,
            sensitive(discharge.getEncounter()),
            provenance(metadata, discharge.getHospital(), acting));
    }

    private PatientTimelineEntryDTO certificateRow(PatientUploadedDocument document, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, "documentType", document.getDocumentType() != null ? document.getDocumentType().name() : null);
        String name = firstNonBlank(document.getDisplayName());
        String summary = MessageUtil.resolveOrRaw("timeline.document.certificate", name != null ? name : "—");
        return row(document.getId(), CATEGORY_DOCUMENT,
            document.getCreatedAt(),
            summary,
            false,
            provenance(metadata, document.getIssuingHospital(), acting));
    }

    private PatientTimelineEntryDTO diagnosisRow(PatientProblem problem, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, problem.getStatus() != null ? problem.getStatus().name() : null);
        putIfNotNull(metadata, "code", problem.getProblemCode());
        putIfNotNull(metadata, META_CLINICIAN, staffName(problem.getRecordedBy()));
        String what = joinCodeAndName(problem.getProblemCode(), problem.getProblemDisplay());
        String label = what != null ? what : MessageUtil.resolveOrRaw("timeline.category.DIAGNOSIS");
        String status = statusLabel(problem.getStatus() != null ? problem.getStatus().name() : null);
        String summary = status.isEmpty() ? label : MessageUtil.resolveOrRaw("timeline.diagnosis.summary", label, status);
        return row(problem.getId(), CATEGORY_DIAGNOSIS,
            coalesce(problem.getCreatedAt(), atStartOfDay(problem.getOnsetDate())),
            summary,
            sensitivityClassifier.effectiveCategory(problem) != null,
            provenance(metadata, problem.getHospital(), acting));
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
        return row(order.getId(), CATEGORY_LAB_ORDER,
            order.getOrderDatetime(),
            summary,
            sensitive(order.getEncounter()),
            provenance(metadata, order.getHospital(), acting));
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
        return row(order.getId(), CATEGORY_IMAGING,
            order.getOrderedAt(),
            summary,
            sensitive(visit),
            provenance(metadata, order.getHospital(), acting));
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
        return row(note.getId(), CATEGORY_NOTE,
            note.getSignedAt(),
            summary.toString(),
            sensitive(note.getEncounter()),
            provenance(metadata, note.getHospital(), acting));
    }

    private PatientTimelineEntryDTO referralRow(GeneralReferral referral, UUID acting) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_STATUS, referral.getStatus() != null ? referral.getStatus().name() : null);
        putIfNotNull(metadata, META_URGENCY, referral.getUrgency() != null ? referral.getUrgency().name() : null);
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
            coalesce(referral.getSubmittedAt(), referral.getCreatedAt()),
            summary,
            sensitive,
            provenance(metadata, referral.getHospital(), acting));
    }

    /** A row written in a sensitive visit is sensitive (the timeline hides it unless asked). */
    private boolean sensitive(Encounter visit) {
        return visit != null && sensitivityClassifier.effectiveCategory(visit) != null;
    }

    /** The row's provenance, as PatientServiceImpl.stampProvenance writes it for the shared rows. */
    private static Map<String, Object> provenance(Map<String, Object> metadata, Hospital hospital, UUID acting) {
        if (hospital != null && hospital.getId() != null) {
            metadata.put("sourceHospitalId", hospital.getId().toString());
            putIfNotNull(metadata, "sourceHospitalName", hospital.getName());
            metadata.put("foreign", !hospital.getId().equals(acting));
        }
        return metadata;
    }

    private static PatientTimelineEntryDTO row(UUID id, String category, LocalDateTime when, String summary,
                                               boolean sensitive, Map<String, Object> metadata) {
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
