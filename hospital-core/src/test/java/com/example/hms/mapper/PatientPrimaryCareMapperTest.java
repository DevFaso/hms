package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientPrimaryCare;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PatientPrimaryCareMapper#toDto} used to call
 * {@code OffsetDateTime.from(e.getCreatedAt())} on a {@link LocalDateTime}.
 * That is not a conversion — a LocalDateTime carries no offset, so
 * {@code OffsetDateTime.from} throws {@code DateTimeException} — which meant
 * every single call to this mapper failed at runtime. There were no tests, so
 * nothing caught it.
 */
class PatientPrimaryCareMapperTest {

    private final PatientPrimaryCareMapper mapper = new PatientPrimaryCareMapper();

    private PatientPrimaryCare entity;
    private LocalDateTime createdAt;

    @BeforeEach
    void setUp() {
        Patient patient = Patient.builder().firstName("Awa").lastName("Kaboré").build();
        patient.setId(UUID.randomUUID());

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());

        User doctor = new User();
        doctor.setId(UUID.randomUUID());
        doctor.setFirstName("Salif");
        doctor.setLastName("Ouédraogo");

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setUser(doctor);

        createdAt = LocalDateTime.of(2026, 8, 23, 14, 30);

        entity = PatientPrimaryCare.builder()
            .patient(patient)
            .hospital(hospital)
            .assignment(assignment)
            .startDate(LocalDate.of(2026, 1, 1))
            .current(true)
            .build();
        entity.setId(UUID.randomUUID());
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt);
    }

    @Test
    @DisplayName("maps without throwing — the whole mapper used to fail here")
    void mapsLocalDateTimeStampsWithoutThrowing() {
        PatientPrimaryCareResponseDTO dto = mapper.toDto(entity);

        assertThat(dto).isNotNull();
        assertThat(dto.getCreatedAt()).isNotNull();
        assertThat(dto.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("keeps the wall-clock reading and attaches the system offset")
    void attachesTheSystemOffsetWithoutShiftingTheInstant() {
        PatientPrimaryCareResponseDTO dto = mapper.toDto(entity);

        // The stored value was written by LocalDateTime.now() on this server,
        // so the correct offset is this server's — and the reading itself must
        // not move.
        assertThat(dto.getCreatedAt().toLocalDateTime()).isEqualTo(createdAt);
        assertThat(dto.getCreatedAt().getOffset())
            .isEqualTo(ZoneId.systemDefault().getRules().getOffset(createdAt));
    }

    @Test
    @DisplayName("a null timestamp maps to null rather than throwing")
    void nullTimestampsMapToNull() {
        entity.setCreatedAt(null);
        entity.setUpdatedAt(null);

        PatientPrimaryCareResponseDTO dto = mapper.toDto(entity);

        assertThat(dto.getCreatedAt()).isNull();
        assertThat(dto.getUpdatedAt()).isNull();
    }
}
