package com.example.hms.payload.dto.clinicalrules;

/**
 * V356: a rule set's text in the portal's three languages; the screen shows the reader's, the sign-off pack the
 * French one.
 */
public record LocalizedRuleText(String en, String fr, String es) {
}
