package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MVP-8: Parameter object collecting every optional filter the
 * cross-tenant audit search accepts. Replaces an 11-parameter service
 * method (PR #228 SonarCloud finding); keeps the controller's
 * {@code @RequestParam} surface flat while letting the service take a
 * single value-class argument.
 *
 * <p>Every field is optional — null / empty means "no filter on this
 * dimension". Constructed via the public canonical record constructor
 * so callers can use named-arg style (Java records support this when
 * built via {@code AuditSearchFilter}-style helpers, otherwise via
 * positional construction).
 */
public record AuditSearchFilter(
    UUID userId,
    String userNameLike,
    List<AuditEventType> eventTypes,
    AuditStatus status,
    UUID hospitalId,
    UUID organizationId,
    UUID impersonatorUserId,
    String entityType,
    String resourceId,
    LocalDateTime fromDate,
    LocalDateTime toDate
) {
    /** All-null filter — useful default when the caller wants every event. */
    public static AuditSearchFilter empty() {
        return new AuditSearchFilter(null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean needsHospitalOrOrgJoin() {
        return hospitalId != null || organizationId != null;
    }
}
