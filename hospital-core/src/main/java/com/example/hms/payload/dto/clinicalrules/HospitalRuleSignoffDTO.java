package com.example.hms.payload.dto.clinicalrules;

import com.example.hms.enums.RuleSignoffStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V356: one recorded sign-off of a rule set at a hospital, as the screen reads it (the revocation reason
 * included: a reason that is required is read back where the record is read).
 */
@Getter
@Builder
@AllArgsConstructor
public class HospitalRuleSignoffDTO {

    private final UUID id;
    /** The version signed. */
    private final String ruleVersion;
    /** SIGNED or REVOKED. */
    private final RuleSignoffStatus status;
    /** The approver, as written. */
    private final String signerName;
    /** The approver's role or title, as printed on the « validée » label. */
    private final String signerTitle;
    /** The approver's account, when they have one. */
    private final UUID signerUserId;
    /** The date the approver signed. */
    private final LocalDate signedOn;
    private final String note;
    /** Who entered it. */
    private final String recordedByName;
    private final LocalDateTime recordedAt;
    private final LocalDateTime revokedAt;
    private final String revokedByName;
    /** Why it was revoked. */
    private final String revokeReason;
}
