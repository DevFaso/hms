package com.example.hms.mapper;

import com.example.hms.enums.PatientStayStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import com.example.hms.payload.dto.RegistrationDeskRow;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The registration mapper had no test at all, which is why a bare 500 on
 * GET /registrations reached production: the one service test mocks the
 * mapper, so this body never executed anywhere in the suite.
 *
 * <p>The cases that matter are the degenerate rows. The schema carries no
 * foreign keys, so both {@code registration.patient_id} and
 * {@code patients.user_id} can point at rows that no longer exist, and either
 * makes the corresponding LAZY association throw on proxy initialisation. One
 * bad row must not take down a whole desk worklist.
 */
class PatientHospitalRegistrationMapperTest {

    private PatientHospitalRegistrationMapper mapper;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        mapper = new PatientHospitalRegistrationMapper();

        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("CHU Bogodogo");
        hospital.setCode("CHU-BGD");
    }

    private PatientHospitalRegistration registrationFor(Patient patient) {
        PatientHospitalRegistration reg = PatientHospitalRegistration.builder()
                .patient(patient)
                .hospital(hospital)
                .active(true)
                .registrationDate(LocalDate.of(2026, 8, 1))
                .stayStatus(PatientStayStatus.ADMITTED)
                .build();
        reg.setId(UUID.randomUUID());
        return reg;
    }

    private Patient patient(User user) {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setFirstName("  Awa  ");
        p.setLastName("Diallo");
        p.setUser(user);
        return p;
    }

    @Test
    @DisplayName("maps a healthy row including the username")
    void healthyRow() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("awa.diallo");

        PatientHospitalRegistrationResponseDTO dto = mapper.toResponseDTO(registrationFor(patient(user)));

        assertThat(dto.getPatientUsername()).isEqualTo("awa.diallo");
        assertThat(dto.getPatientFirstName()).isEqualTo("Awa");
        assertThat(dto.getHospitalName()).isEqualTo("CHU Bogodogo");
        assertThat(dto.isActive()).isTrue();
    }

    @Test
    @DisplayName("a phone-first patient with no email still maps (V107 made email nullable)")
    void nullEmailIsFine() {
        User user = new User();
        user.setUsername("phone.only");
        Patient p = patient(user);
        p.setEmail(null);

        PatientHospitalRegistrationResponseDTO dto = mapper.toResponseDTO(registrationFor(p));

        assertThat(dto.getPatientEmail()).isNull();
        assertThat(dto.getPatientUsername()).isEqualTo("phone.only");
    }

    @Test
    @DisplayName("a null user maps to a null username instead of throwing")
    void nullUser() {
        PatientHospitalRegistrationResponseDTO dto = mapper.toResponseDTO(registrationFor(patient(null)));
        assertThat(dto.getPatientUsername()).isNull();
    }

    @Test
    @DisplayName("a dangling or duplicated user_id degrades the row instead of failing the page")
    void userProxyThrows_rowStillMaps() {
        // Stands in for a Hibernate proxy whose initialisation fails — the shape
        // of both "More than one row with the given identifier" (duplicate
        // user_id) and EntityNotFoundException (dangling user_id).
        Patient exploding = new Patient() {
            @Override
            public User getUser() {
                throw new EntityNotFoundException("Unable to find com.example.hms.model.User with id ...");
            }
        };
        exploding.setId(UUID.randomUUID());
        exploding.setFirstName("Awa");
        exploding.setLastName("Diallo");

        PatientHospitalRegistration reg = registrationFor(exploding);

        assertThatCode(() -> mapper.toResponseDTO(reg)).doesNotThrowAnyException();

        PatientHospitalRegistrationResponseDTO dto = mapper.toResponseDTO(reg);
        assertThat(dto.getPatientUsername()).isNull();
        // The rest of the row must survive — this is a desk worklist, not a profile.
        assertThat(dto.getPatientFirstName()).isEqualTo("Awa");
        assertThat(dto.getHospitalName()).isEqualTo("CHU Bogodogo");
    }

    @Test
    @DisplayName("a null patient maps to nulls rather than throwing")
    void nullPatient() {
        PatientHospitalRegistrationResponseDTO dto = mapper.toResponseDTO(registrationFor(null));

        assertThat(dto.getPatientId()).isNull();
        assertThat(dto.getPatientUsername()).isNull();
        assertThat(dto.getHospitalName()).isEqualTo("CHU Bogodogo");
    }

    @Test
    @DisplayName("null entity maps to null")
    void nullEntity() {
        assertThat(mapper.toResponseDTO(null)).isNull();
    }

    @Test
    @DisplayName("an orphaned patient_id degrades the row instead of failing the read")
    void patientProxyThrows_rowStillMaps() {
        // registration.patient is optional=false and LAZY, so getPatient() hands
        // back a proxy that is never null and throws on first dereference. This
        // stands in for the production row whose patient had been deleted.
        PatientHospitalRegistration reg = new PatientHospitalRegistration() {
            @Override
            public Patient getPatient() {
                throw new EntityNotFoundException("Unable to find com.example.hms.model.Patient with id ...");
            }
        };
        reg.setId(UUID.randomUUID());
        reg.setHospital(hospital);
        reg.setMrn("mrn-ORPHAN1");
        reg.setActive(true);

        assertThatCode(() -> mapper.toResponseDTO(reg)).doesNotThrowAnyException();

        PatientHospitalRegistrationResponseDTO dto = mapper.toResponseDTO(reg);
        assertThat(dto.getPatientId()).isNull();
        assertThat(dto.getPatientFirstName()).isNull();
        // The registration's own columns still render — that is the whole point.
        assertThat(dto.getMri()).isEqualTo("mrn-ORPHAN1");
        assertThat(dto.getHospitalName()).isEqualTo("CHU Bogodogo");
    }

    // ---- desk-list projection overload ----

    private RegistrationDeskRow deskRow(UUID patientId, String username, String first, String last) {
        return new RegistrationDeskRow(
            UUID.randomUUID(), "mrn-DESK001",
            patientId, username, first, last,
            "awa@example.com", "+22670000001", "FEMALE",
            hospital.getId(), hospital.getName(), hospital.getCode(), "Ouagadougou",
            LocalDate.of(2026, 8, 1), true,
            PatientStayStatus.ADMITTED, null,
            "204", "B", "Dr Kabore", null, null);
    }

    @Test
    @DisplayName("maps a healthy desk row, trimming the name fields")
    void deskRow_healthy() {
        PatientHospitalRegistrationResponseDTO dto =
            mapper.toDeskResponseDTO(deskRow(UUID.randomUUID(), "awa.diallo", "  Awa  ", "Diallo"));

        assertThat(dto.getPatientUsername()).isEqualTo("awa.diallo");
        assertThat(dto.getPatientFirstName()).isEqualTo("Awa");
        assertThat(dto.getPatientFullName()).isEqualTo("Awa Diallo");
        assertThat(dto.getHospitalName()).isEqualTo("CHU Bogodogo");
        assertThat(dto.getCurrentRoom()).isEqualTo("204");
        assertThat(dto.isActive()).isTrue();
    }

    @Test
    @DisplayName("an orphaned desk row maps to null patient details, never an exception")
    void deskRow_orphanedPatient() {
        PatientHospitalRegistrationResponseDTO dto =
            mapper.toDeskResponseDTO(deskRow(null, null, null, null));

        assertThat(dto.getPatientId()).isNull();
        assertThat(dto.getPatientUsername()).isNull();
        assertThat(dto.getPatientFullName()).isNull();
        // Everything the registration owns is still there for the desk to act on.
        assertThat(dto.getMri()).isEqualTo("mrn-DESK001");
        assertThat(dto.getHospitalName()).isEqualTo("CHU Bogodogo");
    }

    @Test
    @DisplayName("null desk row maps to null")
    void deskRow_null() {
        assertThat(mapper.toDeskResponseDTO(null)).isNull();
    }
}
