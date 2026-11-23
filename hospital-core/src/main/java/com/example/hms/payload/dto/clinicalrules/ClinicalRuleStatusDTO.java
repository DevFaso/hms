package com.example.hms.payload.dto.clinicalrules;

import com.example.hms.enums.RuleSignoffStatus;

import java.util.List;

/**
 * V356: one provisional rule set as THIS hospital stands on it.
 *
 * @param key               the rule set's key
 * @param version           its current version (what a sign-off is recorded against)
 * @param title             its name, EN/FR/ES (the portal shows the reader's language)
 * @param description       what it does today, EN/FR/ES
 * @param source            the published source (as written) or decision it rests on, EN/FR/ES
 * @param approverRole      who signs it off at a hospital, EN/FR/ES
 * @param enforcedIn        the classes that enforce it
 * @param status            PENDING, SIGNED or REVOKED at this hospital for the current version
 * @param signoff           the sign-off of the current version (signed, or revoked); null when pending
 * @param lastSignedVersion when pending: an earlier version this hospital had signed, else null
 * @param area              the area of the product it belongs to (the screen shows its translated name, not a class)
 * @param sourceUrl         the public page of the main published source, or null
 */
public record ClinicalRuleStatusDTO(
    String key,
    String version,
    LocalizedRuleText title,
    LocalizedRuleText description,
    LocalizedRuleText source,
    LocalizedRuleText approverRole,
    List<String> enforcedIn,
    RuleSignoffStatus status,
    HospitalRuleSignoffDTO signoff,
    String lastSignedVersion,
    String area,
    String sourceUrl) {
}
