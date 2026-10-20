package com.example.hms.security.audit;

import com.example.hms.enums.AuditEventType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.UUID;

/**
 * QA D429: what a write handler knows and the route does not.
 *
 * <p>{@link WriteAuditInterceptor} records every successful write from the
 * route alone, so a POST to {@code /lab-results} became a generic
 * {@code DATA_CREATE} with no patient: the patient is in the body, and the new
 * row's id is in the response. A handler that knows them states them here,
 * and the interceptor writes them on the same single row. Ids only, no PHI.
 *
 * <p>Outside a request (a scheduled job, an HL7 listener) this is a no-op.
 */
public final class WriteAuditDetail {

    static final String ATTRIBUTE = WriteAuditDetail.class.getName();

    /** The specific event, the written resource and its patient (any may be null). */
    public record Detail(AuditEventType eventType, UUID resourceId, UUID patientId) {}

    private WriteAuditDetail() {
    }

    public static void describe(AuditEventType eventType, String resourceId, String patientId) {
        describe(eventType, parse(resourceId), parse(patientId));
    }

    public static void describe(AuditEventType eventType, UUID resourceId, UUID patientId) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(ATTRIBUTE, new Detail(eventType, resourceId, patientId),
                RequestAttributes.SCOPE_REQUEST);
        }
    }

    static Detail of(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof Detail detail ? detail : null;
    }

    private static UUID parse(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
