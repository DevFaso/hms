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
 *   <li>{@link com.example.hms.model.PatientVitalSign} — one capture row → up to 7
 *       Observation resources, one per measured component (temperature, HR, RR, SBP/DBP,
 *       SpO2, glucose, weight). Each is bound to a LOINC code.</li>
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
        if (src.getLabOrder() != null && src.getLabOrder().getPatient() != null
            && src.getLabOrder().getPatient().getId() != null) {
            o.setSubject(new Reference("Patient/" + src.getLabOrder().getPatient().getId()));
        }
        if (src.getResultDate() != null) {
            o.setEffective(new DateTimeType(Date.from(
                src.getResultDate().atZone(ZoneId.systemDefault()).toInstant()
            )));
        }
        // P1 will replace this with proper LOINC binding (gap #5).
        CodeableConcept code = new CodeableConcept();
        var def = src.getLabOrder() == null ? null : src.getLabOrder().getLabTestDefinition();
        if (def != null) {
            if (def.getName() != null) code.setText(def.getName());
            if (def.getTestCode() != null && !def.getTestCode().isBlank()) {
                code.addCoding(new Coding()
                    .setSystem("urn:hms:lab:test-code")
                    .setCode(def.getTestCode())
                    .setDisplay(def.getName()));
            }
        } else {
            code.setText("Laboratory result");
        }
        o.setCode(code);
        if (src.getResultValue() != null) {
            BigDecimal numeric = parseNumeric(src.getResultValue());
            if (numeric != null) {
                Quantity q = new Quantity().setValue(numeric);
                if (src.getResultUnit() != null && !src.getResultUnit().isBlank()) {
                    q.setUnit(src.getResultUnit().trim()).setSystem(UCUM).setCode(src.getResultUnit().trim());
                }
                o.setValue(q);
            } else {
                o.setValue(new org.hl7.fhir.r4.model.StringType(src.getResultValue()));
            }
        }
        if (src.getNotes() != null && !src.getNotes().isBlank()) {
            o.addNote().setText(src.getNotes());
        }
        return o;
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
}
