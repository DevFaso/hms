package com.example.hms.mapper;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * Resilience tests for {@link LabOrderMapper#toLabOrderResponseDTO}.
 *
 * <p>Reproduces the production failure where
 * {@code GET /api/super-admin/recent-activity?limit=10} returned HTTP 500
 * because a {@code LabOrder} row's {@code patient_id} (or other FK) referenced
 * a row that was hard-deleted. The mapper used to do
 * {@code labOrder.getPatient().getFirstName()} unguarded; Hibernate raised
 * {@link EntityNotFoundException} when the lazy proxy initialised, which the
 * global exception handler converted into 500. The whole list response was
 * lost over a single bad row.
 *
 * <p>The fix wraps every lazy association read in
 * {@link com.example.hms.persistence.JpaProxyUtils#safeInit}. These tests
 * stub {@link Hibernate#initialize} to throw on the dangling association and
 * assert the mapper still returns a usable DTO with the bad row's parent
 * fields nulled out.
 */
@ExtendWith(MockitoExtension.class)
class LabOrderMapperResilienceTest {

    private LabOrderMapper mapper;

    private UUID labOrderId;
    private Patient patient;
    private Hospital hospital;
    private LabTestDefinition labTestDefinition;

    @BeforeEach
    void setUp() {
        mapper = new LabOrderMapper();
        labOrderId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Jane");
        patient.setLastName("Roe");
        patient.setEmail("jane.roe@example.com");

        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Test Hospital");

        labTestDefinition = new LabTestDefinition();
        labTestDefinition.setId(UUID.randomUUID());
        labTestDefinition.setName("CBC");
        labTestDefinition.setTestCode("CBC-01");
    }

    private LabOrder buildLabOrder() {
        LabOrder o = LabOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .labTestDefinition(labTestDefinition)
            .status(LabOrderStatus.PENDING)
            .build();
        o.setId(labOrderId);
        return o;
    }

    @Test
    void toLabOrderResponseDTO_danglingPatientFk_returnsDtoWithNulledPatientFields() {
        LabOrder order = buildLabOrder();

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Patient) {
                    throw new EntityNotFoundException("Unable to find Patient");
                }
                return null;
            });

            assertThatNoException().isThrownBy(() -> mapper.toLabOrderResponseDTO(order));

            LabOrderResponseDTO dto = mapper.toLabOrderResponseDTO(order);
            assertThat(dto.getId()).isEqualTo(labOrderId.toString());
            // Patient fields degrade gracefully — endpoint stays 200.
            assertThat(dto.getPatientId()).isNull();
            assertThat(dto.getPatientFullName()).isNull();
            assertThat(dto.getPatientEmail()).isNull();
            // Other associations remain populated.
            assertThat(dto.getHospitalName()).isEqualTo(hospital.getName());
            assertThat(dto.getLabTestName()).isEqualTo(labTestDefinition.getName());
            assertThat(dto.getLabTestCode()).isEqualTo(labTestDefinition.getTestCode());
        }
    }

    @Test
    void toLabOrderResponseDTO_danglingHospitalFk_returnsDtoWithNulledHospitalName() {
        LabOrder order = buildLabOrder();

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Hospital) {
                    throw new EntityNotFoundException("Unable to find Hospital");
                }
                return null;
            });

            LabOrderResponseDTO dto = mapper.toLabOrderResponseDTO(order);
            assertThat(dto.getHospitalName()).isNull();
            // Patient still resolves.
            assertThat(dto.getPatientFullName()).isEqualTo("Jane Roe");
        }
    }

    @Test
    void toLabOrderResponseDTO_jpaObjectRetrievalFailureException_alsoCaught() {
        LabOrder order = buildLabOrder();

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Patient) {
                    throw new JpaObjectRetrievalFailureException(
                        new EntityNotFoundException("Unable to find Patient"));
                }
                return null;
            });

            assertThatNoException().isThrownBy(() -> mapper.toLabOrderResponseDTO(order));
            LabOrderResponseDTO dto = mapper.toLabOrderResponseDTO(order);
            assertThat(dto.getPatientFullName()).isNull();
        }
    }

    @Test
    void toLabOrderResponseDTO_allFksHealthy_populatesFullDto() {
        // Sanity check: when nothing dangles, mapping still produces the
        // expected human-readable fields. Guards against a regression where
        // safeInit accidentally nulls out healthy associations.
        LabOrder order = buildLabOrder();

        LabOrderResponseDTO dto = mapper.toLabOrderResponseDTO(order);

        assertThat(dto.getPatientFullName()).isEqualTo("Jane Roe");
        assertThat(dto.getPatientEmail()).isEqualTo("jane.roe@example.com");
        assertThat(dto.getHospitalName()).isEqualTo("Test Hospital");
        assertThat(dto.getLabTestName()).isEqualTo("CBC");
        assertThat(dto.getLabTestCode()).isEqualTo("CBC-01");
    }
}
