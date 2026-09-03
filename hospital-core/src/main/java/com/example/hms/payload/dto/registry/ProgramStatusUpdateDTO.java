package com.example.hms.payload.dto.registry;

import com.example.hms.enums.ProgramEnrollmentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Move an enrolment out of (or back into) ACTIVE (Tier 2 item 35). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProgramStatusUpdateDTO {

    @NotNull
    private ProgramEnrollmentStatus status;

    /**
     * Why. Required by the service for every closed state — LOST_TO_FOLLOW_UP
     * without "traced twice by phone, once by CHW visit" is a shrug, not an
     * outcome — and refused for a move back to ACTIVE, where the reason field
     * would have nothing to explain.
     */
    @Size(max = 500)
    private String reason;
}
