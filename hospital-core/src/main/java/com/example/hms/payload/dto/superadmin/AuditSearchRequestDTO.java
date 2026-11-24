package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationRegion;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The filters of {@code POST /super-admin/audit-search} and its CSV export, under the names the
 * query parameters had. {@code userName} is a person's login (a patient's is often a phone
 * number), so the filters travel in the body; paging and the CSV row cap stay in the query.
 * Every field is optional; the dates are ISO local date-times read as UTC, as before.
 *
 * <p>{@code unknown} collects any field this record does not name, so the controller can refuse it
 * as {@code @StrictQueryParams} refused an unknown query parameter: a misspelt filter must never
 * widen the search to every row.
 */
public record AuditSearchRequestDTO(
    UUID userId,
    String userName,
    List<String> eventTypes,
    List<String> eventType,
    AuditStatus status,
    UUID hospitalId,
    UUID organizationId,
    UUID impersonatorUserId,
    String entityType,
    String resourceId,
    LocalDateTime fromDate,
    LocalDateTime toDate,
    OrganizationRegion tenantRegion,
    Boolean excludeRoutineSuccesses,
    @JsonAnySetter Map<String, Object> unknown
) {
    /** The field names this record accepts, for the refusal message. */
    public static final String ACCEPTED = "userId, userName, eventTypes, eventType, status, hospitalId, organizationId,"
        + " impersonatorUserId, entityType, resourceId, fromDate, toDate, tenantRegion, excludeRoutineSuccesses";

    /** No filter at all: what an empty body means. */
    public static AuditSearchRequestDTO none() {
        return new AuditSearchRequestDTO(null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, Map.of());
    }
}
