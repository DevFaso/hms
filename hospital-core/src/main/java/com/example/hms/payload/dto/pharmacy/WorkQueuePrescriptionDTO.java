package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Minimal projection of a {@code Prescription} for the pharmacist work-queue.
 * Exposes only the fields the UI needs; avoids leaking PHI or internal
 * relations and prevents recursive JSON serialization from entity graphs.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkQueuePrescriptionDTO {

    private UUID id;
    private String medicationName;
    private String dosage;
    private BigDecimal quantity;
    private String quantityUnit;
    private String status;
    private LocalDateTime createdAt;

    private Patient patient;
    private Staff staff;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Patient {
        private UUID id;
        private String firstName;
        private String lastName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Staff {
        private UUID id;
        private StaffUser user;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StaffUser {
        private UUID id;
        private String firstName;
        private String lastName;
    }
}
