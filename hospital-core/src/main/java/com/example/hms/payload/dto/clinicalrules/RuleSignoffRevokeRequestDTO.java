package com.example.hms.payload.dto.clinicalrules;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * V356: « Révoquer » — a sign-off withdrawn, and why. The rule set reads « provisoire » again.
 *
 * @param reason why the sign-off is withdrawn (kept on the record, never in the audit trail)
 */
public record RuleSignoffRevokeRequestDTO(@NotBlank @Size(min = 3, max = 1000) String reason) {
}
