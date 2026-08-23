package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientPrimaryCare;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientPrimaryCareRequestDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Component
public class PatientPrimaryCareMapper {

    public PatientPrimaryCare toEntity(PatientPrimaryCareRequestDTO dto, Patient p,
                                       Hospital h, UserRoleHospitalAssignment a) {
        return PatientPrimaryCare.builder()
            .patient(p).hospital(h).assignment(a)
            .startDate(Objects.requireNonNullElse(dto.getStartDate(), LocalDate.now()))
            .endDate(dto.getEndDate())
            .current(dto.getEndDate() == null || dto.getEndDate().isAfter(LocalDate.now()))
            .notes(dto.getNotes())
            .build();
    }

    public PatientPrimaryCareResponseDTO toDto(PatientPrimaryCare e) {
        return PatientPrimaryCareResponseDTO.builder()
            .id(e.getId())
            .patientId(e.getPatient().getId())
            .hospitalId(e.getHospital().getId())
            .assignmentId(e.getAssignment().getId())
            .doctorUserId(e.getAssignment().getUser().getId())
            .doctorDisplay(e.getAssignment().getUser().getFirstName() + " " +
                e.getAssignment().getUser().getLastName())
            .startDate(e.getStartDate())
            .endDate(e.getEndDate())
            .current(e.isCurrent())
            .createdAt(toOffset(e.getCreatedAt()))
            .updatedAt(toOffset(e.getUpdatedAt()))
            .build();
    }

    /**
     * BaseEntity stores timestamps as LocalDateTime while this DTO exposes
     * OffsetDateTime. {@code OffsetDateTime.from(LocalDateTime)} does NOT
     * convert — it throws DateTimeException, because a LocalDateTime carries no
     * offset to read — so every call to {@link #toDto} failed at runtime. The
     * offset has to be supplied, and the system zone is the right one: these
     * values were written by {@code LocalDateTime.now()} on this server.
     * Matches the FHIR mappers' atZone(ZoneId.systemDefault()) idiom.
     */
    private static OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}


