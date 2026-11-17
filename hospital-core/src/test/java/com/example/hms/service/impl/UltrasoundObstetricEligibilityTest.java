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

    private static UltrasoundOrderRequestDTO create(com.example.hms.enums.UltrasoundScanType type, String priority) {
        UltrasoundOrderRequestDTO request = new UltrasoundOrderRequestDTO();
        request.setScanType(type);
        request.setPriority(priority);
        return request;
    }

    /** QA D1538 review: OTHER ("not covered by standard categories") need not be prenatal: not judged. */
    @Test
    void anOtherScanForAManIsNotJudged() {
        assertThat(UltrasoundServiceImpl.judgeOnCreate(patient("MALE", 40),
            create(com.example.hms.enums.UltrasoundScanType.OTHER, "ROUTINE"))).isFalse();
        assertThat(UltrasoundServiceImpl.judgeOnCreate(patient("MALE", 40),
            create(com.example.hms.enums.UltrasoundScanType.OTHER, "STAT"))).as("nothing skipped, nothing to audit").isFalse();
    }

    @Test
    void aRoutinePrenatalScanForAManIsRefusedOnThePatient() {
        assertThatThrownBy(() -> UltrasoundServiceImpl.judgeOnCreate(patient("MALE", 40),
                create(com.example.hms.enums.UltrasoundScanType.GROWTH_SCAN, "ROUTINE")))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("patientId"));
    }

    /** QA D1538 review: an emergency prenatal scan for a man or a child is let through and reported skipped. */
    @ParameterizedTest
    @CsvSource({"MALE,40,STAT", "FEMALE,6,URGENT"})
    void anEmergencyPrenatalScanForAnIneligiblePatientIsSkippedAndReported(String gender, int age, String priority) {
        assertThat(UltrasoundServiceImpl.judgeOnCreate(patient(gender, age),
            create(com.example.hms.enums.UltrasoundScanType.ANATOMY_SCAN, priority))).isTrue();
    }

    @Test
    void anEmergencyScanForAWomanSkipsNothing() {
        assertThat(UltrasoundServiceImpl.judgeOnCreate(patient("FEMALE", 28),
            create(com.example.hms.enums.UltrasoundScanType.GROWTH_SCAN, "STAT"))).isFalse();
    }

    /** QA D1538 review: an OTHER scan turned into a prenatal one leaves its exemption: refused on scanType. */
    @Test
    void anOtherScanTurnedPrenatalIsJudgedOnTheScanType() {
        UltrasoundOrder scan = order(patient("MALE", 40), "ROUTINE");
        scan.setScanType(com.example.hms.enums.UltrasoundScanType.OTHER);
        UltrasoundOrderRequestDTO request = edit("ROUTINE");
        request.setScanType(com.example.hms.enums.UltrasoundScanType.GROWTH_SCAN);
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireObstetricPatientOnEdit(scan, request))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("scanType"));
    }

    /** QA D1538 review: an edit that makes an order an emergency prenatal scan for a man flags it. */
    @Test
    void anEditIntoAnEmergencyForAManFlagsIt() {
        UltrasoundOrder scan = order(patient("MALE", 40), "STAT");
        scan.setScanType(com.example.hms.enums.UltrasoundScanType.OTHER);
        UltrasoundOrderRequestDTO request = edit("STAT");
        request.setScanType(com.example.hms.enums.UltrasoundScanType.GROWTH_SCAN);
        assertThat(UltrasoundServiceImpl.requireObstetricPatientOnEdit(scan, request)).isTrue();
        scan.setObstetricRuleSkipped(true);
        assertThat(UltrasoundServiceImpl.requireObstetricPatientOnEdit(scan, request)).as("the reason stands").isTrue();
    }

    /** QA D1538 review: the flag is cleared when its reason is gone. */
    @Test
    void theFlagIsClearedWhenTheReasonIsGone() {
        // The scan becomes OTHER.
        UltrasoundOrder other = order(patient("MALE", 40), "STAT");
        other.setScanType(com.example.hms.enums.UltrasoundScanType.GROWTH_SCAN);
        other.setObstetricRuleSkipped(true);
        UltrasoundOrderRequestDTO toOther = edit("STAT");
        toOther.setScanType(com.example.hms.enums.UltrasoundScanType.OTHER);
        assertThat(UltrasoundServiceImpl.requireObstetricPatientOnEdit(other, toOther)).isFalse();

        // The patient record was corrected: now eligible.
        UltrasoundOrder corrected = order(patient("FEMALE", 30), "STAT");
        corrected.setScanType(com.example.hms.enums.UltrasoundScanType.GROWTH_SCAN);
        corrected.setObstetricRuleSkipped(true);
        assertThat(UltrasoundServiceImpl.requireObstetricPatientOnEdit(corrected, edit("STAT"))).isFalse();

        // No longer an emergency, judged and passing.
        UltrasoundOrder lowered = order(patient("FEMALE", 30), "URGENT");
        lowered.setScanType(com.example.hms.enums.UltrasoundScanType.GROWTH_SCAN);
        lowered.setObstetricRuleSkipped(true);
        assertThat(UltrasoundServiceImpl.requireObstetricPatientOnEdit(lowered, edit("ROUTINE"))).isFalse();
    }

    /**
     * QA D1538 review (round 4): an edit that leaves the priority out keeps the order's own. The request
     * had a default of ROUTINE, so an edit of a STAT order's time alone read as lowering an emergency.
     */
    @Test
    void anEditOfAStatOrdersTimeAloneKeepsItsPriority() {
        UltrasoundOrderRequestDTO timeOnly = new UltrasoundOrderRequestDTO();
        timeOnly.setScheduledTime("10:30");
        assertThat(timeOnly.getPriority()).as("absent, not ROUTINE").isNull();
        assertThat(UltrasoundOrderRequestDTO.builder().build().getPriority()).isNull();

        UltrasoundOrder stat = order(patient("MALE", 40), "STAT");
        stat.setScanType(com.example.hms.enums.UltrasoundScanType.GROWTH_SCAN);
        stat.setObstetricRuleSkipped(true);
        assertThatCode(() -> UltrasoundServiceImpl.requireObstetricPatientOnEdit(stat, timeOnly)).doesNotThrowAnyException();
        assertThat(UltrasoundServiceImpl.requireObstetricPatientOnEdit(stat, timeOnly)).as("still flagged").isTrue();
        assertThat(UltrasoundServiceImpl.normalisedPriority(timeOnly.getPriority(), null)).as("kept").isNull();
    }

    /** QA D1538 review: the priority is stored normalised; a value that names none is refused on priority. */
    @Test
    void thePriorityIsStoredNormalised() {
        assertThat(UltrasoundServiceImpl.normalisedPriority(" stat ", "ROUTINE")).isEqualTo("STAT");
        assertThat(UltrasoundServiceImpl.normalisedPriority("urgent", null)).isEqualTo("URGENT");
        assertThat(UltrasoundServiceImpl.normalisedPriority("  ", "ROUTINE")).isEqualTo("ROUTINE");
        assertThat(UltrasoundServiceImpl.normalisedPriority(null, null)).isNull();
        assertThatThrownBy(() -> UltrasoundServiceImpl.normalisedPriority("ASAP", "ROUTINE"))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("priority");
                assertThat(e.getMessageKey()).isEqualTo("validation.priority.invalid");
            });
    }

    @Test
    void loweringAWomansEmergencyScanIsAccepted() {
        UltrasoundOrder scan = order(patient("FEMALE", 28), "STAT");
        assertThatCode(() -> UltrasoundServiceImpl.requireObstetricPatientOnEdit(scan, edit("ROUTINE")))
            .doesNotThrowAnyException();
    }
}
