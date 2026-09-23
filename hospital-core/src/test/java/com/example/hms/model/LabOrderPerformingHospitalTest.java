package com.example.hms.model;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit gap B1: an order carries the laboratory that performs it, and the
 * tenant predicate admits the ordering hospital AND that laboratory.
 */
class LabOrderPerformingHospitalTest {

    private Hospital ordering;
    private Hospital performing;
    private Hospital third;
    private LabOrder order;

    @BeforeEach
    void setUp() {
        ordering = hospital("Ordering Hospital");
        performing = hospital("Central Laboratory");
        third = hospital("Unrelated Clinic");

        User user = new User();
        user.setId(UUID.randomUUID());
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(ordering);
        assignment.setUser(user);
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);
        staff.setHospital(ordering);
        staff.setAssignment(assignment);
        staff.setJobTitle(JobTitle.DOCTOR);
        staff.setEmploymentType(EmploymentType.FULL_TIME);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(UUID.randomUUID());
        definition.setName("CBC");
        definition.setTestCode("CBC");

        order = LabOrder.builder()
            .patient(patient)
            .orderingStaff(staff)
            .assignment(assignment)
            .hospital(ordering)
            .labTestDefinition(definition)
            .status(LabOrderStatus.ORDERED)
            .orderDatetime(LocalDateTime.now())
            .clinicalIndication("Fatigue")
            .build();
        order.setId(UUID.randomUUID());
    }

    @Test
    void inHouseOrderIsHandledByTheOrderingHospitalOnly() {
        assertThat(order.isPerformedExternally()).isFalse();
        assertThat(order.resolvePerformingHospitalId()).isEqualTo(ordering.getId());
        assertThat(order.isHandledBy(ordering.getId())).isTrue();
        assertThat(order.isHandledBy(performing.getId())).isFalse();
        assertThat(order.isPerformedAt(ordering.getId())).isFalse();
    }

    @Test
    void routedOrderIsHandledByOrderingAndPerformingHospitalsButNotAThird() {
        order.setPerformingHospital(performing);

        assertThat(order.isPerformedExternally()).isTrue();
        assertThat(order.resolvePerformingHospitalId()).isEqualTo(performing.getId());
        assertThat(order.isHandledBy(ordering.getId())).isTrue();
        assertThat(order.isHandledBy(performing.getId())).isTrue();
        assertThat(order.isPerformedAt(performing.getId())).isTrue();
        assertThat(order.isPerformedAt(ordering.getId())).isFalse();
        assertThat(order.isHandledBy(third.getId())).isFalse();
    }

    @Test
    void nullScopeIsSuperAdminAndHandlesEverything() {
        order.setPerformingHospital(performing);
        assertThat(order.isHandledBy(null)).isTrue();
    }

    @Test
    void performingHospitalEqualToOrderingHospitalIsNormalisedToNullOnPersist() {
        order.setPerformingHospital(ordering);

        ReflectionTestUtils.invokeMethod(order, "validate");

        assertThat(order.getPerformingHospital()).isNull();
        assertThat(order.isPerformedExternally()).isFalse();
    }

    @Test
    void testDefinitionOwnedByThePerformingLaboratoryIsAccepted() {
        order.setPerformingHospital(performing);
        order.getLabTestDefinition().setHospital(performing);

        ReflectionTestUtils.invokeMethod(order, "validate");

        assertThat(order.getPerformingHospital()).isSameAs(performing);
    }

    @Test
    void testDefinitionOwnedByAThirdHospitalIsStillRefused() {
        order.setPerformingHospital(performing);
        order.getLabTestDefinition().setHospital(third);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(order, "validate"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("performing hospital");
    }

    @Test
    void mapperExposesBothHospitals() {
        order.setPerformingHospital(performing);

        LabOrderResponseDTO dto = new LabOrderMapper().toLabOrderResponseDTO(order);

        assertThat(dto.getHospitalId()).isEqualTo(ordering.getId().toString());
        assertThat(dto.getHospitalName()).isEqualTo("Ordering Hospital");
        assertThat(dto.getPerformingHospitalId()).isEqualTo(performing.getId().toString());
        assertThat(dto.getPerformingHospitalName()).isEqualTo("Central Laboratory");
    }

    @Test
    void mapperLeavesPerformingHospitalNullForAnInHouseOrder() {
        LabOrderResponseDTO dto = new LabOrderMapper().toLabOrderResponseDTO(order);

        assertThat(dto.getPerformingHospitalId()).isNull();
        assertThat(dto.getPerformingHospitalName()).isNull();
    }

    private static Hospital hospital(String name) {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName(name);
        return hospital;
    }
}
