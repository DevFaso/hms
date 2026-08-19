package com.example.hms.service.integration;

/**
 * Outcome of one POST to a DHIS2 instance.
 *
 * @param httpStatus     HTTP status code returned by DHIS2
 * @param importedCount  rows DHIS2 reported as successfully imported
 *                       (parsed from the import-summary JSON)
 * @param ignoredCount   rows DHIS2 reported as ignored (validation /
 *                       conflict / unauthorized data element)
 * @param body           raw response body — only used for diagnostics
 *                       and never logged in full (may quote per-row
 *                       error messages back to the operator)
 */
public record DhisHttpResponse(
        int httpStatus,
        int importedCount,
        int ignoredCount,
        String body
) {
}
