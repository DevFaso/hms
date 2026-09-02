package com.example.hms.payload.dto.registry;

import com.example.hms.enums.CareProgram;
import com.example.hms.enums.ProgramEnrollmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One registry row (Tier 2 item 35). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProgramEnrollmentResponseDTO {

    private UUID id;
    private UUID hospitalId;

    private UUID patientId;
    private String patientName;
    private String mrn;
    private String phoneNumber;

    private CareProgram program;
    private ProgramEnrollmentStatus status;

    private LocalDate enrolledOn;
    private String enrolledByName;

    private Integer visitCadenceDays;
    private LocalDate lastVisitOn;
    private LocalDate nextExpectedVisit;

    /**
     * Days past the expected visit, server-computed so every client agrees.
     * 0 when not overdue; only ever positive on an ACTIVE enrolment.
     */
    private long overdueDays;

    private String notes;
    private LocalDate closedOn;
    private String closureReason;

    private LocalDateTime createdAt;
}
