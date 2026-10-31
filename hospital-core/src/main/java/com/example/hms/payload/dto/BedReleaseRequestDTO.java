package com.example.hms.payload.dto;

/**
 * Body of DELETE /admissions/{id}/bed (QA D1100): why the inpatient's bed is
 * freed. Sent in the body, not the query string, so the free text a nurse
 * types never lands in access or proxy logs. The service refuses a blank or
 * over-long reason with its own localized message.
 */
public record BedReleaseRequestDTO(String reason) {
}
