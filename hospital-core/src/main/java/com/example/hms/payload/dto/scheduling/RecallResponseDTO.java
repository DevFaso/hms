package com.example.hms.payload.dto.scheduling;

import com.example.hms.enums.RecallSource;
import com.example.hms.enums.RecallStatus;
import com.example.hms.enums.RecallType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One patient recall as the desk sees it (P3 #22). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RecallResponseDTO {

    private UUID id;
    private UUID hospitalId;

    private UUID patientId;
    private String patientName;
    private String mrn;

    private UUID departmentId;
    private String departmentName;

    private UUID preferredProviderId;
    private String preferredProviderName;

    private UUID encounterId;

    private RecallType recallType;
    private RecallStatus status;
    private RecallSource source;

    private LocalDate dueDate;
    private String reason;
    private String notes;

    private LocalDateTime notifiedAt;
    private UUID linkedAppointmentId;
    private LocalDateTime closedAt;

    private LocalDateTime createdAt;
    private String createdBy;
}
