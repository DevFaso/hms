package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D1094: a lab order on a colleague's visit answered 500
 * ("Encounter.staff must match orderingStaff", an entity invariant thrown from
 * {@code @PrePersist}). The rule, as in a real hospital: any clinician of the
 * visit's hospital who may order tests orders on an OPEN visit, whoever leads
 * it. Another patient's visit and a closed visit are refused with a translated
 * 400 by the service, before the entity is ever asked.
 */
@ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
class LabOrderColleagueVisitTest {

    private final Hospital hospital = hospital();
    private final Patient patient = patient();
    private final Staff lead = staff();
    private final Staff colleague = staff();

    private static Hospital hospital() {
        Hospital h = Hospital.builder().build();
        h.setId(UUID.randomUUID());
        return h;
    }

    private static Patient patient() {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        return p;
    }

    private Staff staff() {
        Staff s = new Staff();
        s.setId(UUID.randomUUID());
        s.setHospital(hospital);
        return s;
    }

    private Encounter visit(EncounterStatus status, Patient of) {
        Encounter e = new Encounter();
        e.setId(UUID.randomUUID());
        e.setHospital(hospital);
        e.setPatient(of);
        e.setStaff(lead);
        e.setStatus(status);
        return e;
    }

    @Test
    void aColleaguesOpenVisitTakesTheOrder() {
        Encounter open = visit(EncounterStatus.IN_PROGRESS, patient);

        assertThatCode(() -> LabOrderServiceImpl.requireOrderableEncounter(open, patient, null))
            .doesNotThrowAnyException();
    }

    /** The entity no longer refuses it either (it threw the 500). */
    @Test
    void theEntityAcceptsAColleaguesVisit() throws Exception {
        LabOrder order = new LabOrder();
        order.setHospital(hospital);
        order.setPatient(patient);
        order.setOrderingStaff(colleague);
        order.setEncounter(visit(EncounterStatus.IN_PROGRESS, patient));
        Method consistency = LabOrder.class.getDeclaredMethod("validateEncounterConsistency");
        consistency.setAccessible(true);

        assertThatCode(() -> consistency.invoke(order)).doesNotThrowAnyException();
    }

    @Test
    void anotherPatientsVisitIsATranslatedRefusal() {
        Encounter someoneElses = visit(EncounterStatus.IN_PROGRESS, patient());

        assertThatThrownBy(() -> LabOrderServiceImpl.requireOrderableEncounter(someoneElses, patient, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("another patient");
    }

    @ParameterizedTest
    @EnumSource(value = EncounterStatus.class, names = {"COMPLETED", "CANCELLED", "LEFT_WITHOUT_BEING_SEEN"})
    void aClosedVisitTakesNoNewOrder(EncounterStatus closed) {
        Encounter visit = visit(closed, patient);

        assertThatThrownBy(() -> LabOrderServiceImpl.requireOrderableEncounter(visit, patient, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("closed");
    }

    /** An order already on a visit that has since closed can still be edited (a nurse correcting it). */
    @Test
    void anExistingOrderKeepsItsVisitAfterItCloses() {
        Encounter closed = visit(EncounterStatus.COMPLETED, patient);
        LabOrder existing = new LabOrder();
        existing.setEncounter(closed);

        assertThatCode(() -> LabOrderServiceImpl.requireOrderableEncounter(closed, patient, existing))
            .doesNotThrowAnyException();
    }

    @Test
    void anOrderWithNoVisitIsNotAsked() {
        assertThatCode(() -> LabOrderServiceImpl.requireOrderableEncounter(null, patient, null))
            .doesNotThrowAnyException();
        assertThat(EncounterStatus.CLOSED).contains(EncounterStatus.COMPLETED);
    }
}
