package com.example.hms.payload.dto.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Insurer claims plan PR 3: one refusal by the payer of all or part of a bordereau line: the amount, the class of
 * the reason, the payer's reason as typed, and what the hospital did with it.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InsurerClaimRefusalDTO {

    private UUID id;
    private UUID claimLineId;
    private BigDecimal amount;
    private String reasonClass;
    private String reason;
    private String resolution;
    /** The write-off request the refusal opened, and where it stands. */
    private UUID writeOffId;
    private String writeOffStatus;
    private String recordedByName;
    private LocalDateTime recordedAt;
}
