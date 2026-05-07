package com.example.hms.mapper;

import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * Resilience tests for {@link PrescriptionMapper#toResponseDTO}.
 *
 * <p>Reproduces the production failure where a Prescription's referenced
 * parent (Patient / Staff / Encounter / Hospital) was hard-deleted while the
 * Prescription row still references it. Before the fix the mapper let
 * Hibernate's {@link EntityNotFoundException} propagate, which the global
 * exception handler converted to HTTP 500 — breaking the entire
 * {@code GET /api/prescriptions} list response over a single bad row.
 *
 * <p>The fix wraps every lazy association read in {@code safeInit}, which
 * calls {@link Hibernate#initialize} and degrades to {@code null} when
 * initialisation fails. These tests stub {@code Hibernate.initialize} to
 * throw, then assert the mapper still returns a usable DTO.
 */
class PrescriptionMapperResilienceTest {

    private final PrescriptionMapper mapper = new PrescriptionMapper();

    @Test
    void toResponseDTO_nullPrescription_returnsNull() {
        assertThat(mapper.toResponseDTO(null)).isNull();
    }

    @Test
    void toResponseDTO_handlesNullAssociationsWithoutThrowing() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        // No patient, staff, encounter, hospital — all null.
        // Mapper must still produce a DTO, not NPE.
        assertThatNoException().isThrownBy(() -> {
            PrescriptionResponseDTO dto = mapper.toResponseDTO(p);
            assertThat(dto).isNotNull();
            assertThat(dto.getId()).isEqualTo(p.getId());
            assertThat(dto.getPatientId()).isNull();
            assertThat(dto.getPatientFullName()).isEqualTo("");
            assertThat(dto.getPatientEmail()).isEqualTo("");
            assertThat(dto.getStaffId()).isNull();
            assertThat(dto.getEncounterId()).isNull();
            assertThat(dto.getHospitalId()).isNull();
            assertThat(dto.getHospitalName()).isNull();
        });
    }

    @Test
    void toResponseDTO_danglingPatientFk_degradesToNullPatientFields() {
        Prescription p = buildPrescriptionWithAllAssociations();

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            // Simulate: every Hibernate.initialize call works EXCEPT for the patient,
            // whose underlying row was hard-deleted (dangling FK).
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Patient) {
                    throw new EntityNotFoundException("Unable to find Patient with id " + ((Patient) arg).getId());
                }
                return null;
            });

            PrescriptionResponseDTO dto = mapper.toResponseDTO(p);

            assertThat(dto).isNotNull();
            assertThat(dto.getId()).isEqualTo(p.getId());
            // Patient fields degrade gracefully:
            assertThat(dto.getPatientId()).isNull();
            assertThat(dto.getPatientFullName()).isEqualTo("");
            assertThat(dto.getPatientEmail()).isEqualTo("");
            // Other fields still populated:
            assertThat(dto.getStaffId()).isEqualTo(p.getStaff().getId());
            assertThat(dto.getEncounterId()).isEqualTo(p.getEncounter().getId());
            assertThat(dto.getHospitalId()).isEqualTo(p.getHospital().getId());
        }
    }

    @Test
    void toResponseDTO_danglingStaffFk_degradesStaffFields() {
        Prescription p = buildPrescriptionWithAllAssociations();

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Staff) {
                    throw new EntityNotFoundException("Unable to find Staff with id " + ((Staff) arg).getId());
                }
                return null;
            });

            PrescriptionResponseDTO dto = mapper.toResponseDTO(p);

            assertThat(dto).isNotNull();
            assertThat(dto.getStaffId()).isNull();
            assertThat(dto.getStaffFullName()).isEqualTo("");
            assertThat(dto.getPatientId()).isEqualTo(p.getPatient().getId());
        }
    }

    @Test
    void toResponseDTO_danglingNestedStaffUserFk_degradesStaffName() {
        // Staff exists but its `user` ManyToOne points at a deleted User row.
        Prescription p = buildPrescriptionWithAllAssociations();
        // Wipe the redundant `Staff.name` cache so resolveStaffFullName falls through to user.firstName/lastName.
        p.getStaff().setName(null);

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof User) {
                    throw new EntityNotFoundException("Unable to find User with id " + ((User) arg).getId());
                }
                return null;
            });

            PrescriptionResponseDTO dto = mapper.toResponseDTO(p);

            assertThat(dto).isNotNull();
            assertThat(dto.getStaffId()).isEqualTo(p.getStaff().getId());
            // Staff name comes back empty because the nested User is dangling and
            // there is no cached `Staff.name` — but the response is still 200, not 500.
            assertThat(dto.getStaffFullName()).isEqualTo("");
        }
    }

    @Test
    void toResponseDTO_jpaObjectRetrievalFailureException_alsoCaught() {
        Prescription p = buildPrescriptionWithAllAssociations();

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Encounter) {
                    // Spring sometimes wraps Hibernate's EntityNotFoundException in this.
                    throw new JpaObjectRetrievalFailureException(
                        new EntityNotFoundException("encounter row deleted"));
                }
                return null;
            });

            assertThatNoException().isThrownBy(() -> {
                PrescriptionResponseDTO dto = mapper.toResponseDTO(p);
                assertThat(dto).isNotNull();
                assertThat(dto.getEncounterId()).isNull();
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Test fixture                                                       */
    /* ------------------------------------------------------------------ */

    private Prescription buildPrescriptionWithAllAssociations() {
        UUID patientId = UUID.randomUUID();
        UUID staffId   = UUID.randomUUID();
        UUID userId    = UUID.randomUUID();
        UUID encId     = UUID.randomUUID();
        UUID hospId    = UUID.randomUUID();

        User user = User.builder()
            .username("doc")
            .passwordHash("h")
            .email("doc@example.com")
            .firstName("Ada")
            .lastName("Lovelace")
            .build();
        user.setId(userId);

        Hospital hospital = new Hospital();
        hospital.setId(hospId);
        hospital.setName("General Hospital");

        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setName("Dr. Ada Lovelace");
        staff.setUser(user);
        staff.setHospital(hospital);

        Patient patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("John");
        patient.setLastName("Doe");
        patient.setEmail("john@example.com");

        Encounter encounter = new Encounter();
        encounter.setId(encId);
        encounter.setHospital(hospital);

        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setPatient(patient);
        p.setStaff(staff);
        p.setEncounter(encounter);
        p.setHospital(hospital);
        return p;
    }
}
