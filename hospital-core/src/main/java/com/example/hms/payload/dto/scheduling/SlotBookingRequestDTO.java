package com.example.hms.payload.dto.scheduling;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Book a slot for a patient (P3 #22). The appointment owns the time. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SlotBookingRequestDTO {

    @NotNull(message = "A patient is required to book a slot.")
    private UUID patientId;

    /** Optional; defaults to the slot's visit-type name. */
    @Size(max = 500)
    private String reason;
}
