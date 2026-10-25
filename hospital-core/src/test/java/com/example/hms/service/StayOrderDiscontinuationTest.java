package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.QueueClaimExitActor;
import com.example.hms.enums.QueueClaimReleaseReason;
import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.pharmacy.PreparedFillVoider;
import com.example.hms.service.pharmacy.PrescriptionQueueClaimService;
import com.example.hms.service.pharmacy.partner.WithdrawnOrderPartnerHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D757: a discharge ends the stay's ward orders, never a take-home one. */
@ExtendWith(MockitoExtension.class)
class StayOrderDiscontinuationTest {

    private static final LocalDateTime ADMITTED = LocalDateTime.of(2026, 10, 24, 10, 0);

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PreparedFillVoider preparedFills;
    @Mock private PrescriptionQueueClaimService queueClaims;
    @Mock private WithdrawnOrderPartnerHandler withdrawnOrders;
    @Mock private MessageSource messageSource;

    @InjectMocks private StayOrderDiscontinuation service;

    private Admission stay;
    private Encounter stayEncounter;
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Hospital hospital = Hospital.builder().build();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        stay = new Admission();
        stay.setId(UUID.randomUUID());
        stay.setPatient(patient);
        stay.setHospital(hospital);
        stay.setAdmissionDateTime(ADMITTED);
        stayEncounter = encounter(EncounterType.INPATIENT, stay.getId());
    }

    private static Encounter encounter(EncounterType type, UUID admissionId) {
        Encounter e = new Encounter();
        e.setId(UUID.randomUUID());
        e.setEncounterType(type);
        e.setAdmissionId(admissionId);
        e.setStatus(EncounterStatus.IN_PROGRESS);
        return e;
    }

    private static Prescription order(String name, Encounter on, PrescriptionStatus status, LocalDateTime written) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationName(name);
        rx.setEncounter(on);
        rx.setStatus(status);
        rx.setCreatedAt(written);
        return rx;
    }

    @Test
    void d757_theWardOrdersOfTheStayAreDiscontinuedAtDischarge() {
        // The QA scenario: Paracétamol 1 g q6h and Ceftriaxone 1 g IV q24h,
        // signed on the stay, one already partly filled by the pharmacy.
        Prescription paracetamol = order("Paracétamol", stayEncounter, PrescriptionStatus.SIGNED, ADMITTED.plusHours(1));
        Prescription ceftriaxone = order("Ceftriaxone", stayEncounter, PrescriptionStatus.PARTIALLY_FILLED, ADMITTED.plusHours(1));
        // Paths re-verified: a take-home order, a fully dispensed order, an
        // order of an earlier outpatient visit and an order of another stay.
        Prescription takeHome = order("Amoxicilline", stayEncounter, PrescriptionStatus.SIGNED, ADMITTED.plusDays(2));
        takeHome.setTakeHome(true);
        Prescription dispensed = order("Métronidazole", stayEncounter, PrescriptionStatus.DISPENSED, ADMITTED.plusHours(2));
        Encounter visit = encounter(EncounterType.CONSULTATION, null);
        Prescription outpatient = order("Fer", visit, PrescriptionStatus.SIGNED, ADMITTED.minusDays(10));
        Prescription otherStay = order("Insuline", encounter(EncounterType.INPATIENT, UUID.randomUUID()),
            PrescriptionStatus.SIGNED, ADMITTED.plusHours(3));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(stay.getPatient().getId(), stay.getHospital().getId()))
            .thenReturn(List.of(paracetamol, ceftriaxone, takeHome, dispensed, outpatient, otherStay));
        when(messageSource.getMessage(eq("prescription.discontinuedAtDischarge"), isNull(), anyString(), any()))
            .thenReturn("Arrêtée à la sortie");

        List<Prescription> ended = service.discontinueStayOrders(stay, actor);

        assertThat(ended).containsExactly(paracetamol, ceftriaxone);
        assertThat(paracetamol.getStatus()).isEqualTo(PrescriptionStatus.DISCONTINUED);
        assertThat(ceftriaxone.getStatus()).isEqualTo(PrescriptionStatus.DISCONTINUED);
        assertThat(paracetamol.getWithdrawalReason()).isEqualTo("Arrêtée à la sortie");
        assertThat(takeHome.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
        assertThat(dispensed.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
        assertThat(outpatient.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
        assertThat(otherStay.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
        // As a prescriber's withdrawal: the prepared fill, the claim, the partners.
        verify(preparedFills).voidPreparedFill(ceftriaxone, ReadyCancelReason.PRESCRIPTION_WITHDRAWN);
        verify(queueClaims).releaseOnExit(paracetamol, QueueClaimReleaseReason.WITHDRAWN, actor, QueueClaimExitActor.OTHER);
        verify(withdrawnOrders).withdrawPartnerOffers(paracetamol);
        verify(preparedFills, never()).voidPreparedFill(eq(takeHome), any());
        verify(prescriptionRepository).saveAll(List.of(paracetamol, ceftriaxone));
    }

    @Test
    void d757_theEmergencyOrdersCarriedOntoTheWardEndWithTheStayToo() {
        // The emergency visit closed with the admission (D712), its orders
        // carried on to the ward MAR (D702): they end with the stay as well.
        Encounter ed = encounter(EncounterType.EMERGENCY, null);
        ed.setStatus(EncounterStatus.COMPLETED);
        ed.setCheckoutTimestamp(ADMITTED);
        Prescription edOrder = order("Ceftriaxone", ed, PrescriptionStatus.SIGNED, ADMITTED.minusHours(2));
        // An emergency visit checked out days before the stay keeps its take-home course.
        Encounter oldEd = encounter(EncounterType.EMERGENCY, null);
        oldEd.setStatus(EncounterStatus.COMPLETED);
        oldEd.setCheckoutTimestamp(ADMITTED.minusDays(3));
        Prescription oldEdOrder = order("Ibuprofène", oldEd, PrescriptionStatus.SIGNED, ADMITTED.minusDays(3));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(any(), any()))
            .thenReturn(List.of(edOrder, oldEdOrder));
        when(messageSource.getMessage(anyString(), isNull(), anyString(), any())).thenReturn("x");

        assertThat(service.discontinueStayOrders(stay, actor)).containsExactly(edOrder);
        assertThat(oldEdOrder.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
    }

    @Test
    void anIncompleteStayEndsNothing() {
        stay.setPatient(null);
        assertThat(service.discontinueStayOrders(stay, actor)).isEmpty();
        verify(prescriptionRepository, never()).findByPatient_IdAndHospital_Id(any(), any());
    }
}
