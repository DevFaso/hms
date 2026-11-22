package com.example.hms.payload.dto.clinicalrules;

import java.util.List;
import java.util.UUID;

/**
 * V356: every provisional rule set and where THIS hospital stands on it.
 *
 * @param hospitalId  the hospital the answer is about
 * @param canManage   whether the reader may record or revoke a sign-off here (its hospital admin, or the super-admin)
 * @param signedCount rule sets signed off at their current version
 * @param total       rule sets in the registry
 * @param rules       every rule set, in the registry's order
 */
public record ClinicalRulesOverviewDTO(
    UUID hospitalId,
    boolean canManage,
    int signedCount,
    int total,
    List<ClinicalRuleStatusDTO> rules) {
}
