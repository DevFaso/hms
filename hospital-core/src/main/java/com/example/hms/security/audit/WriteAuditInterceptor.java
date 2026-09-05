package com.example.hms.security.audit;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.AuditEventLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records every successful POST / PUT / PATCH / DELETE made by an
 * authenticated user as an audit row — the write-side twin of
 * {@link PatientAccessAuditInterceptor}.
 *
 * <p>Why an interceptor and not a call in each service: 151 controllers carry
 * write mappings and, by a crude count, about 120 of them sit on services
 * that emit no audit event at all. Every surface added since #431 — bed
 * management, transfusions, cultures, labor, isolation, guarantors, recalls,
 * slot inventory, … — shipped that way, because "remember to audit" is the
 * kind of rule that is only ever enforced in review. The convention here
 * cannot be forgotten: a write is recorded unless somebody wrote
 * {@link WriteAudited#skip()} with a reason.
 *
 * <p>What a row carries: the actor (id, username, primary role, remote
 * address), {@code DATA_CREATE} / {@code DATA_UPDATE} / {@code DATA_DELETE}
 * by HTTP method, an entity type derived from the route (or named on the
 * annotation), the resource id when a path variable holds one, the patient
 * id when the route names one, and a description that is exactly the
 * method plus the matched route pattern. No request body, no query string:
 * both can carry PHI and neither is needed to know that the write happened.
 *
 * <p>What it does not do: replace the specific events services already emit
 * (PATIENT_UPDATE, PRESCRIPTION_CREATED, …). Those controllers opt out so an
 * action is counted once. The disclosure page is unaffected — the generic
 * types classify to no category, by design.
 */
@Slf4j
@Component
public class WriteAuditInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** Route roots that carry no entity meaning of their own. */
    private static final Set<String> NAMESPACE_SEGMENTS = Set.of("api", "super-admin", "admin", "me", "public");
    private static final String PATIENT_ID_VAR = "patientId";
    private static final String ID_VAR = "id";
    private static final String ROLE_PREFIX = "ROLE_";

    private final ObjectProvider<AuditEventLogService> auditServiceProvider;

    @Value("${hms.audit.write.enabled:true}")
    private boolean enabled;

    public WriteAuditInterceptor(ObjectProvider<AuditEventLogService> auditServiceProvider) {
        this.auditServiceProvider = auditServiceProvider;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!enabled) {
            return;
        }
        try {
            recordIfSuccessfulWrite(request, response, handler, ex);
        } catch (Exception failure) {
            // The response is already written; an audit problem must neither
            // become the caller's problem nor pass silently.
            log.warn("[WRITE-AUDIT] Failed to record {} {}: {}",
                request.getMethod(), request.getRequestURI(), failure.getMessage(), failure);
        }
    }

    private void recordIfSuccessfulWrite(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        if (ex != null || !isSuccessfulWrite(request, response)) {
            return;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        WriteAudited annotation = resolveAnnotation(handlerMethod);
        if (annotation != null && annotation.skip()) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails principal)) {
            // Unauthenticated writes (login, password reset, partner webhooks)
            // have their own audit events where they matter and no actor here.
            return;
        }
        AuditEventLogService auditService = auditServiceProvider.getIfAvailable();
        if (auditService == null) {
            return;
        }
        String pattern = matchedPattern(request);
        Map<String, String> pathVariables = uriTemplateVariables(request);
        UUID resourceId = resolveResourceId(pathVariables, annotation);
        UUID patientId = resolvePatientId(request, pathVariables, annotation);

        auditService.logEvent(AuditEventRequestDTO.builder()
            .eventType(eventTypeFor(request.getMethod()))
            .status(AuditStatus.SUCCESS)
            .entityType(entityTypeFor(pattern, annotation))
            .resourceId(resourceId != null ? resourceId.toString() : null)
            .patientId(patientId)
            .userId(principal.getUserId())
            .userName(principal.getUsername())
            .roleName(primaryRole(auth))
            .ipAddress(request.getRemoteAddr())
            .eventDescription(request.getMethod().toUpperCase(Locale.ROOT) + " " + pattern)
            .build());
    }

    private static boolean isSuccessfulWrite(HttpServletRequest request, HttpServletResponse response) {
        if (!WRITE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return false;
        }
        int status = response.getStatus();
        return status >= 200 && status < 300;
    }

    /** Method-level annotation wins over the class-level one. */
    static WriteAudited resolveAnnotation(HandlerMethod handlerMethod) {
        WriteAudited onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), WriteAudited.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), WriteAudited.class);
    }

    static AuditEventType eventTypeFor(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "POST" -> AuditEventType.DATA_CREATE;
            case "DELETE" -> AuditEventType.DATA_DELETE;
            default -> AuditEventType.DATA_UPDATE;
        };
    }

    /**
     * {@code /micro-cultures/{cultureId}/isolates} → {@code MICRO_CULTURE}:
     * the first segment that is not a namespace, upper-snake, singularised.
     * Good enough to group rows by surface; anything finer is the
     * annotation's job.
     */
    static String entityTypeFor(String pattern, WriteAudited annotation) {
        if (annotation != null && !annotation.entity().isBlank()) {
            return annotation.entity();
        }
        for (String segment : pattern.split("/")) {
            if (segment.isBlank() || segment.startsWith("{") || NAMESPACE_SEGMENTS.contains(segment)) {
                continue;
            }
            String snake = segment.replace('-', '_').toUpperCase(Locale.ROOT);
            if (snake.endsWith("IES")) {
                return snake.substring(0, snake.length() - 3) + "Y";
            }
            if (snake.endsWith("S") && !snake.endsWith("SS")) {
                return snake.substring(0, snake.length() - 1);
            }
            return snake;
        }
        return "UNKNOWN";
    }

    private static UUID resolveResourceId(Map<String, String> pathVariables, WriteAudited annotation) {
        if (annotation != null && !annotation.idVar().isBlank()) {
            return parseUuid(pathVariables.get(annotation.idVar()));
        }
        UUID byConvention = parseUuid(pathVariables.get(ID_VAR));
        if (byConvention != null) {
            return byConvention;
        }
        for (Map.Entry<String, String> variable : pathVariables.entrySet()) {
            if (PATIENT_ID_VAR.equals(variable.getKey())) {
                continue;
            }
            UUID candidate = parseUuid(variable.getValue());
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static UUID resolvePatientId(HttpServletRequest request, Map<String, String> pathVariables,
                                         WriteAudited annotation) {
        String name = annotation != null && !annotation.patientIdVar().isBlank()
            ? annotation.patientIdVar() : PATIENT_ID_VAR;
        String fromPath = pathVariables.get(name);
        return parseUuid(fromPath != null ? fromPath : request.getParameter(name));
    }

    private static String matchedPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> uriTemplateVariables(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return attribute instanceof Map ? (Map<String, String>) attribute : Map.of();
    }

    private static UUID parseUuid(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static String primaryRole(Authentication auth) {
        if (auth.getAuthorities() == null) {
            return null;
        }
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith(ROLE_PREFIX))
            .map(a -> a.substring(ROLE_PREFIX.length()))
            .findFirst()
            .orElse(null);
    }
}
