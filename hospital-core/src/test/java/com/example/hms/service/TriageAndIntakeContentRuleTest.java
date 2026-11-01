package com.example.hms.service;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.payload.dto.NursingIntakeRequestDTO;
import com.example.hms.payload.dto.NursingIntakeRequestDTO.MedicationReconciliationEntry;
import com.example.hms.payload.dto.TriageSubmissionRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D1158 / D1188 (rounds 21 and 22): the triage API accepted a weight, a
 * height or a pain score alone as "a vital sign" and ranked the patient; the
 * intake API took a blank medication row as an intake and wrote "Médicament
 * sans nom" into the doctor's notes.
 *
 * <p>The guard half: every property of the triage request is classified as a
 * vital sign (counts), recorded with one (does not count alone), or the
 * triage's own data; and each is proven against the real rule, set alone. A
 * new property fails here until someone decides.
 */
class TriageAndIntakeContentRuleTest {

    /** The portal's six: "Saisissez au moins un signe vital". */
    private static final Set<String> VITALS = Set.of(
        "temperatureCelsius", "heartRateBpm", "respiratoryRateBpm", "systolicBpMmHg", "diastolicBpMmHg", "spo2Percent");

    /** Recorded with a vital sign, never instead of one. */
    private static final Set<String> NOT_ALONE = Set.of(
        "weightKg", "heightCm", "onOxygen", "consciousnessLevel", "painScale", "fundalHeightCm", "chiefComplaint",
        "fallRisk", "fallRiskScore", "roomAssignment");

    /** The rank itself and the class property. */
    private static final Set<String> METADATA = Set.of("class", "esiScore", "bloodPressureOrdered");

    @Test
    void everyTriagePropertyIsClassified() {
        Set<String> unclassified = new TreeSet<>();
        for (PropertyDescriptor p : new BeanWrapperImpl(new TriageSubmissionRequestDTO()).getPropertyDescriptors()) {
            if (!VITALS.contains(p.getName()) && !NOT_ALONE.contains(p.getName()) && !METADATA.contains(p.getName())) {
                unclassified.add(p.getName());
            }
        }
        assertThat(unclassified).as("triage request properties nobody classified (QA D1188)").isEmpty();
    }

    @Test
    void eachTriagePropertyAloneIsAVitalSignExactlyAsListed() {
        List<String> wrong = new ArrayList<>();
        for (PropertyDescriptor p : new BeanWrapperImpl(new TriageSubmissionRequestDTO()).getPropertyDescriptors()) {
            String name = p.getName();
            if (METADATA.contains(name) || p.getWriteMethod() == null) {
                continue;
            }
            TriageSubmissionRequestDTO request = new TriageSubmissionRequestDTO();
            request.setEsiScore(3);
            new BeanWrapperImpl(request).setPropertyValue(name, sample(p.getPropertyType()));
            boolean accepted = accepted(request);
            if (accepted != VITALS.contains(name)) {
                wrong.add(name + (accepted ? " alone was accepted as a vital sign" : " alone was refused"));
            }
        }
        assertThat(wrong).as("QA D1188: the triage rule and the classification disagree").isEmpty();
    }

    @Test
    void theQaBodiesAreRefusedWithTheTranslatedVitalsMessage() {
        for (TriageSubmissionRequestDTO body : List.of(
                TriageSubmissionRequestDTO.builder().esiScore(4).weightKg(72.0).build(),
                TriageSubmissionRequestDTO.builder().esiScore(3).heightCm(175.0).build(),
                TriageSubmissionRequestDTO.builder().esiScore(3).painScale(0).build(),
                TriageSubmissionRequestDTO.builder().esiScore(3).onOxygen(Boolean.TRUE).build())) {
            assertThatThrownBy(() -> EncounterServiceImpl.requireTriageFindings(body))
                .isInstanceOf(FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("triage.vitals.required");
        }
    }

    @Test
    void aMedicationRowWithNoNameIsDropped() {
        NursingIntakeRequestDTO request = new NursingIntakeRequestDTO();
        MedicationReconciliationEntry blank = new MedicationReconciliationEntry();
        MedicationReconciliationEntry empty = MedicationReconciliationEntry.builder().medicationName("  ").build();
        MedicationReconciliationEntry doseOnly = MedicationReconciliationEntry.builder().dosage("500 mg").build();
        MedicationReconciliationEntry named = MedicationReconciliationEntry.builder().medicationName("Metformine").build();
        request.setMedications(new ArrayList<>(java.util.Arrays.asList(blank, empty, doseOnly, null, named)));

        assertThat(EncounterServiceImpl.namedMedications(request)).containsExactly(named);
        request.setMedications(List.of(blank, empty));
        assertThat(EncounterServiceImpl.namedMedications(request)).isEmpty();
        assertThat(EncounterServiceImpl.namedMedications(new NursingIntakeRequestDTO())).isEmpty();
    }

    private static boolean accepted(TriageSubmissionRequestDTO request) {
        try {
            EncounterServiceImpl.requireTriageFindings(request);
            return true;
        } catch (FieldBusinessException refused) {
            return false;
        }
    }

    private static Object sample(Class<?> type) {
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.TRUE;
        }
        if (type == Integer.class) {
            return 1;
        }
        if (type == Double.class) {
            return 70.0;
        }
        if (type == String.class) {
            return "x";
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        throw new IllegalStateException("no sample for " + type);
    }

    @Test
    void eachVitalSignAloneIsEnough() {
        assertThatCode(() -> EncounterServiceImpl.requireTriageFindings(
            TriageSubmissionRequestDTO.builder().esiScore(2).spo2Percent(91).build())).doesNotThrowAnyException();
    }
}
