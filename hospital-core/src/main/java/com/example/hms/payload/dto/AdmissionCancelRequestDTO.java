package com.example.hms.payload.dto;

/**
 * Body of DELETE /admissions/{id} (QA D1381, round 32): why the stay is
 * cancelled. Real hospitals record why any stay is voided; the reason is kept
 * on the admission with who and when. Sent in the body, not the query string,
 * so the free text never lands in access or proxy logs. The service refuses a
 * blank or over-long reason on {@code reason} with its own localized message.
 */
public record AdmissionCancelRequestDTO(String reason) {
}
