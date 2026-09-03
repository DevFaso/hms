package com.example.hms.mapper;

import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.ProgramEnrollment;
import com.example.hms.payload.dto.registry.ProgramEnrollmentResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Enrolment row → registry DTO (Tier 2 item 35), extracted from the service
 * per the house convention so mapping stays independently testable.
 *
 * <p>{@code today} is a parameter, not a clock read: the overdue count is a
 * clinical statement and the service's fixed-clock tests must be able to
 * assert exactly what it said.
 */
@Component
public class ProgramEnrollmentMapper {

    public ProgramEnrollmentResponseDTO toDto(ProgramEnrollment e, UUID hospitalId, LocalDate today) {
        if (e == null) {
            return null;
        }
        Patient patient = e.getPatient();
        String enrolledByName = null;
        if (e.getEnrolledBy() != null && e.getEnrolledBy().getUser() != null) {
            var user = e.getEnrolledBy().getUser();
            enrolledByName = (user.getFirstName() + " " + user.getLastName()).trim();
        }
        long overdueDays = 0;
        if (e.getStatus() == ProgramEnrollmentStatus.ACTIVE
            && e.getNextExpectedVisit() != null
            && e.getNextExpectedVisit().isBefore(today)) {
            overdueDays = ChronoUnit.DAYS.between(e.getNextExpectedVisit(), today);
        }
        return ProgramEnrollmentResponseDTO.builder()
            .id(e.getId())
            .hospitalId(e.getHospital().getId())
            .patientId(patient.getId())
            .patientName(patient.getFullName())
            .mrn(patient.getMrnForHospital(hospitalId))
            .phoneNumber(patient.getPhoneNumberPrimary())
            .program(e.getProgram())
            .status(e.getStatus())
            .enrolledOn(e.getEnrolledOn())
            .enrolledByName(enrolledByName)
            .visitCadenceDays(e.getVisitCadenceDays())
            .lastVisitOn(e.getLastVisitOn())
            .nextExpectedVisit(e.getNextExpectedVisit())
            .overdueDays(overdueDays)
            .notes(e.getNotes())
            .closedOn(e.getClosedOn())
            .closureReason(e.getClosureReason())
            .createdAt(e.getCreatedAt())
            .build();
    }
}
