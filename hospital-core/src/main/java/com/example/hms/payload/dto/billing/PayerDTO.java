package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PayerType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Billing-money plan PR 6: a payer with its plans and their rules.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerDTO {

    private UUID id;
    private UUID hospitalId;
    private String code;
    /** As the hospital typed it, never translated (D-8). */
    private String name;
    private PayerType payerType;
    private boolean active;
    private LocalDateTime deactivatedAt;
    private String deactivatedByName;
    /** As the admin or accountant typed it. */
    private String deactivationReason;
    private LocalDateTime createdAt;
    private String createdByName;
    private LocalDateTime updatedAt;
    private String updatedByName;
    private List<PayerPlanDTO> plans;
}
