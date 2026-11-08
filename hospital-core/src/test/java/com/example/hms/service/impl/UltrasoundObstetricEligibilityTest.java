package com.example.hms.service.impl;

import com.example.hms.enums.ClinicalPriority;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderRequestDTO;
import com.example.hms.utility.ObstetricEligibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D1538 (round 40): a prenatal scan for a man or a 6-year-old was accepted (201). The order is
 * judged by the shared rule with its priority as the emergency; an edit that lowers an emergency
 * scan is judged again.
 */
class UltrasoundObstetricEligibilityTest {

    private static Patient patient(String gender, Integer ageYears) {
        Patient p = new Patient();
        p.setGender(gender);
        if (ageYears != null) {
            p.setDateOfBirth(LocalDate.now().minusYears(ageYears).minusDays(1));
        }
        return p;
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAT", "URGENT", " stat ", "urgent"})
    void urgentAndStatAreEmergencies(String code) {
        assertThat(ClinicalPriority.isEmergency(code)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ROUTINE", "routine", "ASAP"})
    void routineBlankAndUnknownAreNot(String code) {
        assertThat(ClinicalPriority.isEmergency(code)).isFalse();
    }

    @Test
    void theSharedRuleRefusesARoutineScanAndNeverAnEmergency() {
        Patient man = patient("MALE", 56);
        assertThatThrownBy(() -> ObstetricEligibility.requireObstetricEligible(man, "patientId",
                ClinicalPriority.isEmergency("ROUTINE")))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("patientId"));
        assertThatCode(() -> ObstetricEligibility.requireObstetricEligible(patient("FEMALE", 6), "patientId",
                ClinicalPriority.isEmergency("STAT")))
            .doesNotThrowAnyException();
    }

    private static UltrasoundOrder order(Patient patient, String priority) {
        UltrasoundOrder order = new UltrasoundOrder();
        order.setPatient(patient);
        order.setPriority(priority);
        return order;
    }

    private static UltrasoundOrderRequestDTO edit(String priority) {
        UltrasoundOrderRequestDTO request = new UltrasoundOrderRequestDTO();
        request.setPriority(priority);
        return request;
    }

    /** An emergency scan lowered to routine leaves its exemption: refused on the priority. */
    @ParameterizedTest
    @CsvSource({"STAT,ROUTINE", "URGENT,ROUTINE", "STAT,''"})
    void loweringAnEmergencyScanIsJudgedAgain(String stored, String requested) {
        UltrasoundOrder scan = order(patient("MALE", 40), stored);
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireObstetricPatientOnEdit(scan, edit(requested)))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("priority");
                assertThat(e.getMessageKey()).isEqualTo(ObstetricEligibility.MESSAGE_KEY);
            });
    }

    /** An edit that keeps the emergency, leaves the priority out, or never had one is not judged. */
    @ParameterizedTest
    @CsvSource(value = {"STAT,URGENT", "URGENT,STAT", "STAT,NULL", "ROUTINE,ROUTINE", "ROUTINE,STAT"}, nullValues = "NULL")
    void anEditThatDoesNotLeaveAnEmergencyIsNotJudged(String stored, String requested) {
        UltrasoundOrder scan = order(patient("MALE", 40), stored);
        assertThatCode(() -> UltrasoundServiceImpl.requireObstetricPatientOnEdit(scan, edit(requested)))
            .doesNotThrowAnyException();
    }

    @Test
    void loweringAWomansEmergencyScanIsAccepted() {
        UltrasoundOrder scan = order(patient("FEMALE", 28), "STAT");
        assertThatCode(() -> UltrasoundServiceImpl.requireObstetricPatientOnEdit(scan, edit("ROUTINE")))
            .doesNotThrowAnyException();
    }
}
