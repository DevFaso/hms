package com.example.hms.mapper;

import com.example.hms.enums.PatientStayStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
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
 * <p>The cases that matter are the degenerate rows — a dangling or duplicated
 * {@code clinical.patients.user_id} makes {@code Patient.user} throw on proxy
 * initialisation, and one bad row must not take down a whole desk worklist.
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
}
