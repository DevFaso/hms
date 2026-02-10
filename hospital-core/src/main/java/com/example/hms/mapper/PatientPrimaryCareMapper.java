package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientPrimaryCare;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientPrimaryCareRequestDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
            .createdAt(OffsetDateTime.from(e.getCreatedAt()))
            .updatedAt(OffsetDateTime.from(e.getUpdatedAt()))
            .build();
    }
}


