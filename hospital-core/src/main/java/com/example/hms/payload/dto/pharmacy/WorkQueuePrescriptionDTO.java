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
    private String frequency;
    private BigDecimal quantity;
    private String quantityUnit;
    private String status;
    private LocalDateTime createdAt;

    private Patient patient;
    private Staff staff;

    /**
     * Refill context for the dispensing decision. Null on a prescription that
     * has never had a refill request, which keeps the payload unchanged for
     * first fills.
     */
    private Refill refill;

    /**
     * What the pharmacist needs in order to decide whether to hand medication
     * over: how many fills this authorization still has, and what the
     * prescriber last decided about the patient's request.
     *
     * <p>A denial or a hold matters as much as an approval here — a patient can
     * arrive at the counter asking for a refill their doctor refused, and until
     * now nothing in the pharmacy UI would have said so.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Refill {
        /** Refills the prescriber authorized when writing the prescription. */
        private Integer allowed;
        /** Refills still available under that original authorization. */
        private Integer remaining;
        /** Fills released by an approved refill request so far. */
        private Integer used;
        /** Latest decision: REQUESTED, PAUSED, APPROVED, DENIED, DISPENSED, CANCELLED. */
        private String lastStatus;
        /** The prescriber's note on that decision — the reason for a hold or denial. */
        private String lastProviderNotes;
        private LocalDateTime lastDecidedAt;
        /** True when this fill is sitting in the queue because a refill was approved. */
        private boolean awaitingRefillPickup;
    }

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
