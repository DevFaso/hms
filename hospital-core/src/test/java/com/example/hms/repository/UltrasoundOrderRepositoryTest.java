package com.example.hms.repository;

import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.UltrasoundOrderStatus;
import com.example.hms.enums.UltrasoundScanType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class UltrasoundOrderRepositoryTest {

    @Autowired
    private UltrasoundOrderRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private Hospital primaryHospital;
    private Patient primaryPatient;
    private UltrasoundOrder orderedOrder;
    private UltrasoundOrder completedOrder;
    private UltrasoundOrder reportAvailableOrder;
    private UltrasoundOrder cancelledOrder;
    private UltrasoundOrder secondaryHospitalOrder;

    @BeforeEach
    void setUp() {
        Organization organization = persistOrganization("ACME HEALTH");
        primaryHospital = persistHospital("HSP-001", organization);
        Hospital secondaryHospital = persistHospital("HSP-002", organization);

        primaryPatient = persistPatient("patient.primary@example.com", "555-0001", organization.getId(), primaryHospital.getId());
        Patient secondaryPatient = persistPatient("patient.secondary@example.com", "555-0002", organization.getId(), secondaryHospital.getId());

        LocalDateTime now = LocalDateTime.now();

        orderedOrder = persistOrder(primaryPatient, primaryHospital,
            UltrasoundOrderStatus.ORDERED, UltrasoundScanType.ANATOMY_SCAN,
            now.minusDays(5), LocalDate.now().plusDays(1), false);

        completedOrder = persistOrder(primaryPatient, primaryHospital,
            UltrasoundOrderStatus.COMPLETED, UltrasoundScanType.DOPPLER_STUDY,
            now.minusDays(3), LocalDate.now().plusDays(2), true);

        reportAvailableOrder = persistOrder(primaryPatient, primaryHospital,
            UltrasoundOrderStatus.REPORT_AVAILABLE, UltrasoundScanType.GROWTH_SCAN,
            now.minusDays(1), LocalDate.now().plusDays(3), true);

        cancelledOrder = persistOrder(primaryPatient, primaryHospital,
            UltrasoundOrderStatus.CANCELLED, UltrasoundScanType.BIOPHYSICAL_PROFILE,
            now.minusDays(2), LocalDate.now().plusDays(4), false);

        secondaryHospitalOrder = persistOrder(secondaryPatient, secondaryHospital,
            UltrasoundOrderStatus.SCHEDULED, UltrasoundScanType.HIGH_RISK_FOLLOW_UP,
            now.minusDays(4), LocalDate.now().plusDays(1), true);

        entityManager.flush();
    }

    @Test
    void shouldFindMostRecentOrderForPatient() {
        assertThat(repository.findFirstByPatient_IdOrderByOrderedDateDesc(primaryPatient.getId()))
            .isPresent()
            .get()
            .isEqualTo(reportAvailableOrder);
    }

    @Test
    void shouldResolvePatientScopedQueries() {
        List<UltrasoundOrder> ordersByPatient = repository.findAllByPatientId(primaryPatient.getId());
        assertThat(ordersByPatient)
            .extracting(UltrasoundOrder::getId)
            .containsExactly(
                reportAvailableOrder.getId(),
                cancelledOrder.getId(),
                completedOrder.getId(),
                orderedOrder.getId()
            );

        assertThat(repository.findByStatusOrderByOrderedDateDesc(UltrasoundOrderStatus.ORDERED))
            .extracting(UltrasoundOrder::getId)
            .containsExactly(orderedOrder.getId());

        assertThat(repository.findByPatientIdAndStatus(primaryPatient.getId(), UltrasoundOrderStatus.COMPLETED))
            .containsExactly(completedOrder);

        assertThat(repository.findByPatientIdAndScanType(primaryPatient.getId(), UltrasoundScanType.DOPPLER_STUDY))
            .containsExactly(completedOrder);

        assertThat(repository.countByPatientId(primaryPatient.getId()))
            .isEqualTo(3L); // CANCELLED orders should be excluded from the count
    }

    @Test
    void shouldHonorSchedulingAndRiskFilters() {
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(3);

        assertThat(repository.findOrdersScheduledBetween(start, end))
            .extracting(UltrasoundOrder::getId)
            .containsExactlyInAnyOrder(
                orderedOrder.getId(),
                completedOrder.getId(),
                reportAvailableOrder.getId(),
                secondaryHospitalOrder.getId()
            );

        assertThat(repository.findAllHighRiskOrders())
            .extracting(UltrasoundOrder::getId)
            .containsExactlyInAnyOrder(
                completedOrder.getId(),
                reportAvailableOrder.getId(),
                secondaryHospitalOrder.getId()
            );

        assertThat(repository.findAllHighRiskOrders(primaryHospital.getId()))
            .extracting(UltrasoundOrder::getId)
            .containsExactlyInAnyOrder(
                completedOrder.getId(),
                reportAvailableOrder.getId()
            );
    }

    @Test
    void shouldFilterByHospitalAndStatus() {
        assertThat(repository.findByHospitalId(primaryHospital.getId()))
            .extracting(UltrasoundOrder::getId)
            .containsExactly(
                reportAvailableOrder.getId(),
                cancelledOrder.getId(),
                completedOrder.getId(),
                orderedOrder.getId()
            );

        assertThat(repository.findAllByHospitalId(primaryHospital.getId()))
            .extracting(UltrasoundOrder::getId)
            .containsExactly(
                reportAvailableOrder.getId(),
                cancelledOrder.getId(),
                completedOrder.getId(),
                orderedOrder.getId()
            );

        assertThat(repository.findOrdersWithReportsAvailableByHospital(primaryHospital.getId()))
            .containsExactly(reportAvailableOrder);

        assertThat(repository.findPendingOrders(primaryHospital.getId()))
            .containsExactly(orderedOrder);
    }

    private Organization persistOrganization(String code) {
        Organization organization = Organization.builder()
            .name(code + " Network")
            .code(code)
            .type(OrganizationType.HEALTHCARE_NETWORK)
            .build();
        return entityManager.persistAndFlush(organization);
    }

    private Hospital persistHospital(String code, Organization organization) {
        Hospital hospital = Hospital.builder()
            .name("Hospital " + code)
            .code(code)
            .organization(organization)
            .build();
        return entityManager.persistAndFlush(hospital);
    }

    private Patient persistPatient(String email, String phone, UUID organizationId, UUID hospitalId) {
        User user = User.builder()
            .username(email)
            .passwordHash("hashed")
            .email(email)
            .phoneNumber(phone)
            .firstName("Test")
            .lastName("User")
            .build();
        user = entityManager.persistAndFlush(user);

        Patient patient = Patient.builder()
            .firstName("Test")
            .lastName("Patient")
            .dateOfBirth(LocalDate.now().minusYears(30))
            .phoneNumberPrimary(phone)
            .email(email)
            .gender("F")
            .user(user)
            .organizationId(organizationId)
            .hospitalId(hospitalId)
            .build();

        return entityManager.persistAndFlush(patient);
    }

    private UltrasoundOrder persistOrder(
        Patient patient,
        Hospital hospital,
        UltrasoundOrderStatus status,
        UltrasoundScanType scanType,
        LocalDateTime orderedDate,
        LocalDate scheduledDate,
        boolean highRisk
    ) {
        UltrasoundOrder order = UltrasoundOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .status(status)
            .scanType(scanType)
            .orderedDate(orderedDate)
            .orderedBy("Dr. Example")
            .clinicalIndication("Routine monitoring")
            .scheduledDate(scheduledDate)
            .scheduledTime("09:00")
            .isHighRiskPregnancy(highRisk)
            .highRiskNotes(highRisk ? "Requires additional monitoring" : null)
            .priority(highRisk ? "URGENT" : "ROUTINE")
            .build();

        return entityManager.persist(order);
    }
}
