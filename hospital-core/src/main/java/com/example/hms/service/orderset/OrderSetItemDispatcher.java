package com.example.hms.service.orderset;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.service.ImagingOrderService;
import com.example.hms.service.LabOrderService;
import com.example.hms.service.PrescriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Translates a single JSONB order-set item into a real order via the
 * existing {@link PrescriptionService} / {@link LabOrderService} /
 * {@link ImagingOrderService}. The dispatcher fills in safe defaults
 * for required DTO fields the JSONB schema does not carry (e.g.
 * {@code clinicalIndication}, {@code primaryDiagnosisCode}) using
 * the order set's name and the admission's primary diagnosis.
 *
 * <p>Routing the medication branch through {@code PrescriptionService}
 * means the existing CDS rule engine (drug-drug, allergy, pediatric
 * dose) fires for every order-set medication — preserving the
 * critical-block contract and {@code forceOverride} semantics from
 * P1 #3 without bespoke wiring here.
 *
 * <p>Item types DIET / ACTIVITY / MONITORING are recognised but emit
 * a {@link DispatchResult#skipped(String)} with a reason — they have
 * no equivalent order entity yet and are captured separately as
 * encounter notes in v0. Future migrations may map them to structured
 * orders.
 */
@Component
public class OrderSetItemDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(OrderSetItemDispatcher.class);

    /** Order-item type discriminators recognised in the JSONB payload. */
    public static final String TYPE_MEDICATION = "MEDICATION";
    public static final String TYPE_LAB = "LAB";
    public static final String TYPE_IMAGING = "IMAGING";
    public static final String TYPE_DIET = "DIET";
    public static final String TYPE_ACTIVITY = "ACTIVITY";
    public static final String TYPE_MONITORING = "MONITORING";

    /** Fallback ICD-10 used when the admission has no primary diagnosis. */
    private static final String FALLBACK_DIAGNOSIS_CODE = "Z00.00";

    /** Common prefix for indication / question / note narratives generated from a set. */
    private static final String NARRATIVE_PREFIX = "Per order set: ";

    private final PrescriptionService prescriptionService;
    private final LabOrderService labOrderService;
    private final ImagingOrderService imagingOrderService;

    public OrderSetItemDispatcher(
        PrescriptionService prescriptionService,
        LabOrderService labOrderService,
        ImagingOrderService imagingOrderService
    ) {
        this.prescriptionService = prescriptionService;
        this.labOrderService = labOrderService;
        this.imagingOrderService = imagingOrderService;
    }

    /**
     * Apply a single order item against the supplied context.
     * Throws on critical CDS blocks (the medication branch propagates
     * {@code CdsCriticalBlockException} from {@code PrescriptionService})
     * so the surrounding {@code @Transactional} boundary in
     * {@code AdmissionOrderSetServiceImpl#applyToAdmission} rolls the
     * whole bundle back.
     */
    public DispatchResult dispatch(Map<String, Object> item, OrderSetApplicationContext ctx, Locale locale) {
        String type = string(item.get("orderType"));
        if (type == null) {
            return DispatchResult.skipped("missing orderType");
        }
        switch (type.toUpperCase(Locale.ROOT)) {
            case TYPE_MEDICATION:
                return dispatchMedication(item, ctx, locale);
            case TYPE_LAB:
                return dispatchLab(item, ctx, locale);
            case TYPE_IMAGING:
                return dispatchImaging(item, ctx);
            case TYPE_DIET, TYPE_ACTIVITY, TYPE_MONITORING:
                logger.info("orderset item {} type={} captured as note (deferred fan-out)", ctx.orderSetId(), type);
                return DispatchResult.skipped("type " + type + " captured as encounter note in v0");
            default:
                logger.warn("orderset item {} unknown type={}", ctx.orderSetId(), type);
                return DispatchResult.skipped("unknown orderType " + type);
        }
    }

    private DispatchResult dispatchMedication(Map<String, Object> item, OrderSetApplicationContext ctx, Locale locale) {
        String name = string(item.get("medicationName"));
        if (name == null || name.isBlank()) return DispatchResult.skipped("medication missing medicationName");

        PrescriptionRequestDTO req = new PrescriptionRequestDTO();
        req.setPatientId(ctx.patientId());
        req.setStaffId(ctx.orderingStaffId());
        req.setEncounterId(ctx.encounterId());
        req.setMedicationName(name);
        req.setMedicationCode(string(item.get("medicationCode")));
        req.setDosage(combineDose(item));
        req.setFrequency(string(item.get("frequency")));
        req.setDuration(string(item.get("duration")));
        req.setNotes(NARRATIVE_PREFIX + ctx.orderSetName());
        req.setForceOverride(ctx.forceOverride());

        PrescriptionResponseDTO created = prescriptionService.createPrescription(req, locale);
        return DispatchResult.medication(created.getId(), created.getCdsAdvisories());
    }

    private DispatchResult dispatchLab(Map<String, Object> item, OrderSetApplicationContext ctx, Locale locale) {
        String testName = firstNonBlank(string(item.get("orderName")), string(item.get("testName")));
        if (testName == null) return DispatchResult.skipped("lab missing orderName/testName");

        LabOrderRequestDTO req = new LabOrderRequestDTO();
        req.setPatientId(ctx.patientId());
        req.setHospitalId(ctx.hospitalId());
        req.setEncounterId(ctx.encounterId());
        req.setTestName(testName);
        req.setTestCode(firstNonBlank(string(item.get("orderCode")), string(item.get("testCode"))));
        req.setStatus("PENDING");
        req.setPriority(firstNonBlank(string(item.get("priority")), "ROUTINE"));
        req.setClinicalIndication(NARRATIVE_PREFIX + ctx.orderSetName());
        req.setMedicalNecessityNote(ctx.orderSetDescription() != null
            ? ctx.orderSetDescription()
            : "Bundled lab order applied from protocol template " + ctx.orderSetName());
        req.setPrimaryDiagnosisCode(ctx.primaryDiagnosisCode() != null
            ? ctx.primaryDiagnosisCode()
            : FALLBACK_DIAGNOSIS_CODE);

        String createdId = labOrderService.createLabOrder(req, locale).getId();
        return DispatchResult.lab(UUID.fromString(createdId));
    }

    private DispatchResult dispatchImaging(Map<String, Object> item, OrderSetApplicationContext ctx) {
        String studyType = string(item.get("studyType"));
        if (studyType == null || studyType.isBlank()) return DispatchResult.skipped("imaging missing studyType");

        ImagingModality modality = parseModality(string(item.get("modality")));
        if (modality == null) return DispatchResult.skipped("imaging missing/invalid modality");

        ImagingOrderRequestDTO req = new ImagingOrderRequestDTO();
        req.setPatientId(ctx.patientId());
        req.setHospitalId(ctx.hospitalId());
        req.setEncounterId(ctx.encounterId());
        req.setModality(modality);
        req.setStudyType(studyType);
        req.setBodyRegion(string(item.get("bodyRegion")));
        req.setPriority(parsePriority(string(item.get("priority"))));
        req.setClinicalQuestion(NARRATIVE_PREFIX + ctx.orderSetName());

        ImagingOrderResponseDTO created = imagingOrderService.createOrder(req, ctx.orderingStaffId());
        return DispatchResult.imaging(created.getId());
    }

    /* ------------------------------- helpers ----------------------------- */

    private static String string(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }

    private static String combineDose(Map<String, Object> item) {
        String dose = string(item.get("dose"));
        String route = string(item.get("route"));
        if (dose == null && route == null) return null;
        if (dose == null) return route;
        if (route == null) return dose;
        return dose + " " + route;
    }

    private static ImagingModality parseModality(String value) {
        if (value == null) return null;
        try {
            return ImagingModality.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static ImagingOrderPriority parsePriority(String value) {
        if (value == null) return ImagingOrderPriority.ROUTINE;
        try {
            return ImagingOrderPriority.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ImagingOrderPriority.ROUTINE;
        }
    }
}
