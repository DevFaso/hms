package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Resilience tests for {@link ImagingOrderMapper#toResponseDTO}.
 *
 * <p>Reproduces the production failure where {@code GET /api/imaging/orders}
 * returned HTTP 500 because an {@code ImagingOrder} row's {@code patient_id}
 * referenced a row that was hard-deleted. The mapper used to do
 * {@code order.getPatient().getMrnForHospital(...)} unguarded; Hibernate
 * raised {@link EntityNotFoundException} when the lazy proxy initialised,
 * which the global exception handler converted into 500. The whole list
 * response was lost over a single bad row.
 *
 * <p>The fix wraps every lazy association read in
 * {@link com.example.hms.persistence.JpaProxyUtils#safeInit}, plus a
 * second-order try/catch around {@link Patient#getMrnForHospital(UUID)} for
 * dangling FKs in the patient's lazy {@code hospitalRegistrations}
 * collection (a registration whose hospital_id points at a deleted Hospital
 * row).
 */
@ExtendWith(MockitoExtension.class)
class ImagingOrderMapperResilienceTest {

    private ImagingOrderMapper mapper;

    private UUID orderId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        mapper = new ImagingOrderMapper();
        orderId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Jane");
        patient.setLastName("Roe");

        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Test Hospital");
    }

    private ImagingOrder buildOrder() {
        ImagingOrder o = ImagingOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .modality(com.example.hms.enums.ImagingModality.CT)
            .studyType("CT_HEAD")
            .build();
        o.setId(orderId);
        return o;
    }

    @Test
    void toResponseDTO_danglingPatientFk_returnsDtoWithNulledPatientFields() {
        ImagingOrder order = buildOrder();

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Patient) {
                    throw new EntityNotFoundException("Unable to find Patient");
                }
                return null;
            });

            assertThatNoException().isThrownBy(() -> mapper.toResponseDTO(order));

            ImagingOrderResponseDTO dto = mapper.toResponseDTO(order);
            assertThat(dto.getId()).isEqualTo(orderId);
            // Patient fields degrade gracefully — endpoint stays 200.
            assertThat(dto.getPatientId()).isNull();
            assertThat(dto.getPatientDisplayName()).isNull();
            assertThat(dto.getPatientMrn()).isNull();
            // Hospital still resolves.
            assertThat(dto.getHospitalId()).isEqualTo(hospital.getId());
            assertThat(dto.getHospitalName()).isEqualTo(hospital.getName());
        }
    }

    @Test
    void toResponseDTO_danglingHospitalFk_returnsDtoWithNulledHospitalFields() {
        ImagingOrder order = buildOrder();

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Hospital) {
                    throw new EntityNotFoundException("Unable to find Hospital");
                }
                return null;
            });

            ImagingOrderResponseDTO dto = mapper.toResponseDTO(order);
            assertThat(dto.getHospitalId()).isNull();
            assertThat(dto.getHospitalName()).isNull();
            // Patient still resolves; MRN cannot be computed without hospitalId.
            assertThat(dto.getPatientId()).isEqualTo(patient.getId());
            assertThat(dto.getPatientMrn()).isNull();
        }
    }

    @Test
    void toResponseDTO_jpaObjectRetrievalFailureException_alsoCaught() {
        ImagingOrder order = buildOrder();

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Patient) {
                    throw new JpaObjectRetrievalFailureException(
                        new EntityNotFoundException("Unable to find Patient"));
                }
                return null;
            });

            assertThatNoException().isThrownBy(() -> mapper.toResponseDTO(order));
            ImagingOrderResponseDTO dto = mapper.toResponseDTO(order);
            assertThat(dto.getPatientDisplayName()).isNull();
        }
    }

    @Test
    void toResponseDTO_secondOrderDanglingRegistrationFk_returnsDtoWithNulledMrn() {
        // The second-order risk: even after Patient initialises, the lazy
        // hospitalRegistrations collection inside Patient#getMrnForHospital
        // can still throw if a registration row references a deleted hospital.
        // The mapper's try/catch around getMrnForHospital must swallow that
        // and degrade to a null MRN without sinking the whole response.
        Patient throwingPatient = spy(patient);
        when(throwingPatient.getMrnForHospital(any()))
            .thenThrow(new EntityNotFoundException("dangling hospitalRegistration FK"));

        ImagingOrder order = ImagingOrder.builder()
            .patient(throwingPatient)
            .hospital(hospital)
            .modality(com.example.hms.enums.ImagingModality.CT)
            .studyType("CT_HEAD")
            .build();
        order.setId(orderId);

        assertThatNoException().isThrownBy(() -> mapper.toResponseDTO(order));
        ImagingOrderResponseDTO dto = mapper.toResponseDTO(order);
        assertThat(dto.getPatientMrn()).isNull();
        // Patient + hospital identity still resolve.
        assertThat(dto.getPatientId()).isEqualTo(patient.getId());
        assertThat(dto.getHospitalId()).isEqualTo(hospital.getId());
    }

    @Test
    void toResponseDTO_allFksHealthy_populatesFullDto() {
        ImagingOrder order = buildOrder();

        ImagingOrderResponseDTO dto = mapper.toResponseDTO(order);

        assertThat(dto.getId()).isEqualTo(orderId);
        assertThat(dto.getPatientId()).isEqualTo(patient.getId());
        assertThat(dto.getPatientDisplayName()).isEqualTo("Roe, Jane");
        assertThat(dto.getHospitalId()).isEqualTo(hospital.getId());
        assertThat(dto.getHospitalName()).isEqualTo("Test Hospital");
        assertThat(dto.getModality()).isEqualTo(com.example.hms.enums.ImagingModality.CT);
    }
}
