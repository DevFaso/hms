package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.LabSpecimenStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.service.pharmacy.DispenseVerificationService;
import com.example.hms.service.scheduling.PatientRecallServiceImpl;
import com.example.hms.utility.HospitalCalendar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Round 23 rules of fix/r23-backend that are pure functions: QA D1204 (the
 * refusal for a rejected specimen), D1215 (waitlist range, recall due date)
 * and D1221 (the wrong-drug message names the drug in the reader's language).
 */
class R23BackendRulesTest {

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    /* ── D1204 ── */

    private static LabOrder order() {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(new LabTestDefinition());
        return order;
    }

    private static LabSpecimen specimen(LabSpecimenStatus status) {
        LabSpecimen s = new LabSpecimen();
        s.setId(UUID.randomUUID());
        s.setStatus(status);
        return s;
    }

    @Test
    void d1204_aRejectedSpecimenGetsItsOwnRefusal() {
        LabOrder order = order();
        LabSpecimenRepository specimens = mock(LabSpecimenRepository.class);
        when(specimens.findByLabOrder_Id(order.getId())).thenReturn(List.of(specimen(LabSpecimenStatus.REJECTED)));

        assertThatThrownBy(() -> LabResultServiceImpl.requireSpecimenTaken(LabOrderStatus.ORDERED, order, specimens))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("labresult.specimen.rejected");
    }

    @Test
    void d1204_neverCollectedStillSaysNoSpecimen() {
        LabOrder order = order();
        LabSpecimenRepository specimens = mock(LabSpecimenRepository.class);
        when(specimens.findByLabOrder_Id(order.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> LabResultServiceImpl.requireSpecimenTaken(LabOrderStatus.PENDING, order, specimens))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("labresult.specimen.required");
    }

    @Test
    void d1204_aCollectedOrderOrAPointOfCareTestIsNotRefused() {
        LabSpecimenRepository specimens = mock(LabSpecimenRepository.class);
        assertThatCode(() -> LabResultServiceImpl.requireSpecimenTaken(LabOrderStatus.COLLECTED, order(), specimens))
            .doesNotThrowAnyException();
        LabOrder poc = order();
        poc.getLabTestDefinition().setPointOfCare(true);
        assertThatCode(() -> LabResultServiceImpl.requireSpecimenTaken(LabOrderStatus.ORDERED, poc, specimens))
            .doesNotThrowAnyException();
    }

    /* ── D1215 ── */

    @Test
    void d1215_aReversedWaitlistRangeIsRefusedOnTheEndDate() {
        Hospital hospital = new Hospital();
        LocalDate today = HospitalCalendar.today(hospital);
        LocalDate from = today.plusDays(19);
        LocalDate to = today.plusDays(9);
        assertThatThrownBy(() -> ReceptionServiceImpl.requireOfferableRange(from, to, hospital))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "requestedDateTo")
            .extracting("messageKey").isEqualTo("waitlist.dateRange.reversed");
    }

    @Test
    void d1215_aWaitlistRangeThatAlreadyEndedIsRefused() {
        Hospital hospital = new Hospital();
        LocalDate today = HospitalCalendar.today(hospital);
        LocalDate from = today.minusDays(20);
        LocalDate to = today.minusDays(1);
        assertThatThrownBy(() -> ReceptionServiceImpl.requireOfferableRange(from, to, hospital))
            .isInstanceOf(FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("waitlist.dateRange.past");
    }

    @Test
    void d1215_anOpenOrOrderedRangeIsAccepted() {
        Hospital hospital = new Hospital();
        LocalDate today = HospitalCalendar.today(hospital);
        assertThatCode(() -> ReceptionServiceImpl.requireOfferableRange(today, today, hospital)).doesNotThrowAnyException();
        assertThatCode(() -> ReceptionServiceImpl.requireOfferableRange(today, null, hospital)).doesNotThrowAnyException();
        assertThatCode(() -> ReceptionServiceImpl.requireOfferableRange(null, today.plusDays(3), hospital))
            .doesNotThrowAnyException();
    }

    @Test
    void d1215_aRecallDueYearsAgoIsRefusedAndOneDueLastWeekIsKept() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        assertThatThrownBy(() -> PatientRecallServiceImpl.requireRecentDueDate(LocalDate.of(2020, 1, 1), today))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "dueDate")
            .extracting("messageKey").isEqualTo("recall.dueDate.tooFarInPast");
        assertThatCode(() -> PatientRecallServiceImpl.requireRecentDueDate(today.minusDays(7), today))
            .doesNotThrowAnyException();
        assertThatCode(() -> PatientRecallServiceImpl.requireRecentDueDate(
                today.minusDays(PatientRecallServiceImpl.PAST_DUE_TOLERANCE_DAYS), today))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> PatientRecallServiceImpl.requireRecentDueDate(
                today.minusDays(PatientRecallServiceImpl.PAST_DUE_TOLERANCE_DAYS + 1L), today))
            .isInstanceOf(FieldBusinessException.class);
    }

    /* ── D1221 ── */

    @Test
    void d1221_theWrongDrugIsNamedInTheReadersLanguage() {
        MedicationCatalogItem amoxicillin = new MedicationCatalogItem();
        amoxicillin.setNameFr("Amoxicilline");
        amoxicillin.setGenericName("Amoxicillin");
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(DispenseVerificationService.describe(amoxicillin)).isEqualTo("Amoxicilline");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(DispenseVerificationService.describe(amoxicillin)).isEqualTo("Amoxicillin");
    }
}
