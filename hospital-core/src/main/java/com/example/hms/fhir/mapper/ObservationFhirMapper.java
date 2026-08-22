package com.example.hms.fhir.mapper;

import com.example.hms.model.LabResult;
import com.example.hms.model.PatientVitalSign;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Maps two source domains into FHIR R4 {@code Observation}:
 * <ul>
 *   <li>{@link com.example.hms.model.PatientVitalSign} — one capture row → one
 *       Observation resource per measured component (temperature, HR, RR, SBP/DBP,
 *       SpO2, glucose, weight, height, head circumference). Each is bound to a
 *       LOINC code.</li>
 *   <li>{@link com.example.hms.model.LabResult} — single Observation per result row
 *       using the lab order test name as the {@code code.text} until LOINC binding is
 *       added in P1 gap #5.</li>
 * </ul>
 *
 * <p>FHIR resource ids are namespaced ({@code vital-{uuid}-{component}} or {@code labresult-{uuid}})
 * so that vitals derived from the same row remain individually addressable.
 */
@Component
public class ObservationFhirMapper {

    private static final String LOINC = "http://loinc.org";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category";

    public List<Observation> toFhir(PatientVitalSign src) {
        List<Observation> out = new ArrayList<>();
        if (src == null) return out;
        addNumeric(out, src, "temperature",   "8310-5", "Body temperature",      "Cel",   asBd(src.getTemperatureCelsius()));
        addNumeric(out, src, "heart-rate",    "8867-4", "Heart rate",            "/min",  src.getHeartRateBpm() == null ? null : BigDecimal.valueOf(src.getHeartRateBpm()));
        addNumeric(out, src, "resp-rate",     "9279-1", "Respiratory rate",      "/min",  src.getRespiratoryRateBpm() == null ? null : BigDecimal.valueOf(src.getRespiratoryRateBpm()));
        addNumeric(out, src, "sbp",           "8480-6", "Systolic blood pressure",  "mm[Hg]", src.getSystolicBpMmHg() == null ? null : BigDecimal.valueOf(src.getSystolicBpMmHg()));
        addNumeric(out, src, "dbp",           "8462-4", "Diastolic blood pressure", "mm[Hg]", src.getDiastolicBpMmHg() == null ? null : BigDecimal.valueOf(src.getDiastolicBpMmHg()));
        addNumeric(out, src, "spo2",          "59408-5","Oxygen saturation",     "%",     src.getSpo2Percent() == null ? null : BigDecimal.valueOf(src.getSpo2Percent()));
        addNumeric(out, src, "glucose",       "2339-0", "Glucose [Mass/volume] in Blood", "mg/dL", src.getBloodGlucoseMgDl() == null ? null : BigDecimal.valueOf(src.getBloodGlucoseMgDl()));
        addNumeric(out, src, "weight",        "29463-7","Body weight",           "kg",    asBd(src.getWeightKg()));
        addNumeric(out, src, "height",        "8302-2", "Body height",           "cm",    asBd(src.getHeightCm()));
        addNumeric(out, src, "head-circ",     "9843-4", "Head Occipital-frontal circumference", "cm", asBd(src.getHeadCircumferenceCm()));
        return out;
    }

    public Observation toFhir(LabResult src) {
        if (src == null) return null;
        Observation o = new Observation();
        o.setId("labresult-" + (src.getId() == null ? "" : src.getId()));
        o.setStatus(src.isReleased()
            ? Observation.ObservationStatus.FINAL
            : Observation.ObservationStatus.PRELIMINARY);
        o.addCategory(category("laboratory", "Laboratory"));
        setLabSubject(o, src);
        setLabEffective(o, src);
        o.setCode(buildLabCode(src));
        setLabValue(o, src);
        if (src.getNotes() != null && !src.getNotes().isBlank()) {
            o.addNote().setText(src.getNotes());
        }
        return o;
    }

    private static void setLabSubject(Observation o, LabResult src) {
        if (src.getLabOrder() == null || src.getLabOrder().getPatient() == null
            || src.getLabOrder().getPatient().getId() == null) return;
        o.setSubject(new Reference("Patient/" + src.getLabOrder().getPatient().getId()));
    }

    private static void setLabEffective(Observation o, LabResult src) {
        if (src.getResultDate() == null) return;
        o.setEffective(new DateTimeType(Date.from(
            src.getResultDate().atZone(ZoneId.systemDefault()).toInstant()
        )));
    }

    private static CodeableConcept buildLabCode(LabResult src) {
        CodeableConcept code = new CodeableConcept();
        var def = src.getLabOrder() == null ? null : src.getLabOrder().getLabTestDefinition();
        if (def == null) {
            code.setText("Laboratory result");
            return code;
        }
        if (def.getName() != null) code.setText(def.getName());
        // LOINC coding (P1 #1) — emitted as the primary coding when present so
        // OpenHIE/DHIS2 nodes can resolve the observation against an authoritative
        // value set. Local urn:hms:lab:test-code remains as a secondary identifier
        // so internal callers that index by the formulary code still work.
        if (def.getLoincCode() != null && !def.getLoincCode().isBlank()) {
            code.addCoding(new Coding()
                .setSystem(LOINC)
                .setCode(def.getLoincCode())
                .setDisplay(def.getLoincDisplay() != null ? def.getLoincDisplay() : def.getName()));
        }
        if (def.getTestCode() != null && !def.getTestCode().isBlank()) {
            code.addCoding(new Coding()
                .setSystem("urn:hms:lab:test-code")
                .setCode(def.getTestCode())
                .setDisplay(def.getName()));
        }
        return code;
    }

    private static void setLabValue(Observation o, LabResult src) {
        if (src.getResultValue() == null) return;
        BigDecimal numeric = parseNumeric(src.getResultValue());
        if (numeric == null) {
            o.setValue(new org.hl7.fhir.r4.model.StringType(src.getResultValue()));
            return;
        }
        Quantity q = new Quantity().setValue(numeric);
        if (src.getResultUnit() != null && !src.getResultUnit().isBlank()) {
            String unit = src.getResultUnit().trim();
            q.setUnit(unit).setSystem(UCUM).setCode(unit);
        }
        o.setValue(q);
    }

    /* ---------- helpers ---------- */

    private static void addNumeric(
        List<Observation> sink,
        PatientVitalSign src,
        String idSuffix,
        String loincCode,
        String display,
        String ucum,
        BigDecimal value
    ) {
        if (value == null) return;
        Observation o = new Observation();
        o.setId("vital-" + (src.getId() == null ? "" : src.getId()) + "-" + idSuffix);
        o.setStatus(Observation.ObservationStatus.FINAL);
        o.addCategory(category("vital-signs", "Vital Signs"));
        if (src.getPatient() != null && src.getPatient().getId() != null) {
            o.setSubject(new Reference("Patient/" + src.getPatient().getId()));
        }
        if (src.getRecordedAt() != null) {
            o.setEffective(new DateTimeType(Date.from(
                src.getRecordedAt().atZone(ZoneId.systemDefault()).toInstant()
            )));
        }
        o.setCode(new CodeableConcept().addCoding(new Coding()
            .setSystem(LOINC).setCode(loincCode).setDisplay(display)));
        o.setValue(new Quantity()
            .setValue(value)
            .setUnit(ucum)
            .setSystem(UCUM)
            .setCode(ucum));
        sink.add(o);
    }

    private static CodeableConcept category(String code, String display) {
        return new CodeableConcept().addCoding(new Coding()
            .setSystem(CATEGORY_SYSTEM).setCode(code).setDisplay(display));
    }

    private static BigDecimal asBd(Double d) {
        return d == null ? null : BigDecimal.valueOf(d);
    }

    private static BigDecimal parseNumeric(String raw) {
        try {
            return new BigDecimal(raw.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /* ===================== Write direction (FHIR → entity) ===================== */

    /**
     * Apply the FHIR-mutable subset of fields from an inbound FHIR
     * Observation onto an existing {@link LabResult}. Returns the same
     * entity for chaining; the caller is responsible for persisting +
     * audit emission.
     *
     * <p><strong>Intentionally narrow.</strong> Only
     * {@code Observation.note[0].text} is honored. The mapping appends
     * the inbound text to the existing {@code notes} column (joined
     * with {@code " | "}) so external annotations accumulate rather
     * than overwrite the local note from the clinical UI.
     *
     * <p><strong>Not honored:</strong> {@code status}, {@code code},
     * {@code value}, {@code subject}, {@code effective}, {@code category}.
     * Release / sign / acknowledge transitions on a lab result mutate
     * state-machine timestamps + actor stamps that the FHIR PUT path
     * has no signer for; those flow through the clinical release UI or
     * the HL7 MLLP ingest path.
     */
    public LabResult applyFhirLabResultUpdates(LabResult existing, org.hl7.fhir.r4.model.Observation src) {
        if (existing == null || src == null) return existing;
        if (!src.hasNote() || src.getNote().isEmpty()) return existing;
        String inbound = src.getNoteFirstRep().getText();
        if (inbound == null || inbound.trim().isEmpty()) return existing;
        String trimmed = inbound.trim();
        String current = existing.getNotes();
        if (current == null || current.isBlank()) {
            existing.setNotes(trimmed);
            return existing;
        }
        if (current.contains(trimmed)) return existing;
        existing.setNotes(current + " | " + trimmed);
        return existing;
    }
}
