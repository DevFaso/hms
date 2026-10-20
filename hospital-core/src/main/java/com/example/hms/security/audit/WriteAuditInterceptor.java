package com.example.hms.security.audit;

import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

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
 * address) — resolved through {@link ControllerAuthUtils} so a Keycloak
 * {@code JwtAuthenticationToken} counts as much as a legacy
 * {@code CustomUserDetails} principal; {@code DATA_CREATE} /
 * {@code DATA_UPDATE} / {@code DATA_DELETE} by HTTP method; an entity type
 * derived from the route (or named on the annotation); the resource id when
 * a path variable holds one; the patient id when the route names one; and,
 * when the request is scoped to a hospital the actor holds an assignment
 * at, that assignment and hospital, so per-hospital audit views find the
 * row. The description is exactly the method plus the matched route
 * pattern. No request body, no query string: both can carry PHI and neither
 * is needed to know that the write happened.
 *
 * <p>POST is not always a write in this API — {@code /search},
 * {@code /filter}, {@code /candidates} and a few more are reads that carry
 * a body. Those route tails are recognised and skipped; anything else that
 * reads under POST opts out per method with a reason.
 *
 * <p>What it does not do: replace the specific events services already emit
 * (PATIENT_UPDATE, PRESCRIPTION_CREATED, …). Those handlers opt out so an
 * action is counted once — per handler, never per controller, because a
 * controller whose create emits a specific event usually has an update or a
 * sub-resource that emits nothing.
 */
@Slf4j
@Component
public class WriteAuditInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** Route roots that carry no entity meaning of their own. */
    private static final Set<String> NAMESPACE_SEGMENTS = Set.of("api", "super-admin", "admin", "me", "public");
    /**
     * Last literal segment of a POST route that is a read with a request
     * body, not a write. Kept short and literal on purpose; a read under any
     * other name opts out with {@code @WriteAudited(skip = true, reason = …)}.
     */
    static final Set<String> READ_ONLY_POST_TAILS = Set.of(
        "search", "filter", "candidates", "check", "calculate-risk", "lookup", "query", "preview", "validate");
    private static final String PATIENT_ID_VAR = "patientId";
    static final String RESOURCE_ID_ATTRIBUTE = WriteAuditInterceptor.class.getName() + ".resourceId";
    static final String PATIENT_ID_ATTRIBUTE = WriteAuditInterceptor.class.getName() + ".patientId";
    private static final String ID_VAR = "id";
    private static final String ROLE_PREFIX = "ROLE_";

    private final ObjectProvider<AuditEventLogService> auditServiceProvider;
    private final ObjectProvider<ControllerAuthUtils> authUtilsProvider;
    private final ObjectProvider<UserRoleHospitalAssignmentRepository> assignmentRepositoryProvider;

    @Value("${hms.audit.write.enabled:true}")
    private boolean enabled;

    public WriteAuditInterceptor(ObjectProvider<AuditEventLogService> auditServiceProvider,
                                 ObjectProvider<ControllerAuthUtils> authUtilsProvider,
                                 ObjectProvider<UserRoleHospitalAssignmentRepository> assignmentRepositoryProvider) {
        this.auditServiceProvider = auditServiceProvider;
        this.authUtilsProvider = authUtilsProvider;
        this.assignmentRepositoryProvider = assignmentRepositoryProvider;
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
        String pattern = matchedPattern(request);
        if (isReadOnlyPost(request.getMethod(), pattern)) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID actorId = resolveActorId(auth);
        if (auth == null || actorId == null) {
            // Unauthenticated writes (login, password reset, partner webhooks)
            // have their own audit events where they matter and no actor here.
            return;
        }
        AuditEventLogService auditService = auditServiceProvider.getIfAvailable();
        if (auditService == null) {
            return;
        }
        Map<String, String> pathVariables = uriTemplateVariables(request);
        UUID resourceId = resolveResourceId(pathVariables, annotation);
        if (resourceId == null) {
            resourceId = attributeUuid(request, RESOURCE_ID_ATTRIBUTE);
        }
        UUID patientId = resolvePatientId(request, pathVariables, annotation);
        UserRoleHospitalAssignment assignment = resolveAssignment(actorId);

        auditService.logEvent(AuditEventRequestDTO.builder()
            .eventType(eventTypeFor(request.getMethod(), annotation))
            .status(AuditStatus.SUCCESS)
            .entityType(entityTypeFor(pattern, annotation))
            .resourceId(resourceId != null ? resourceId.toString() : null)
            .patientId(patientId)
            .userId(actorId)
            .userName(auth.getName())
            // No hospitalName here: the audit service fills it from assignmentId
            // inside its own transaction. Reading the LAZY hospital in
            // afterCompletion threw "no session" and lost the row (dev, 2026-09-05).
            .assignmentId(assignment != null ? assignment.getId() : null)
            .roleName(roleNameOf(assignment, auth))
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

    /** A POST whose route ends in a read verb ({@code /search}, {@code /filter}, …) reads; it is not recorded. */
    static boolean isReadOnlyPost(String method, String pattern) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        String[] segments = pattern.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (segments[i].isBlank() || segments[i].startsWith("{")) {
                continue;
            }
            return READ_ONLY_POST_TAILS.contains(segments[i]);
        }
        return false;
    }

    /** Method-level annotation wins over the class-level one. */
    static WriteAudited resolveAnnotation(HandlerMethod handlerMethod) {
        WriteAudited onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), WriteAudited.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), WriteAudited.class);
    }

    /**
     * Legacy {@code CustomUserDetails} and Keycloak {@code JwtAuthenticationToken}
     * both resolve — the same rule {@link ControllerAuthUtils#resolveUserId}
     * applies everywhere else. Without the utils bean (a slice) nothing is
     * attributable and nothing is recorded.
     */
    private UUID resolveActorId(Authentication auth) {
        ControllerAuthUtils authUtils = authUtilsProvider.getIfAvailable();
        if (auth == null || authUtils == null) {
            return null;
        }
        return authUtils.resolveUserId(auth).orElse(null);
    }

    /**
     * The actor's active assignment at the hospital this request is pinned
     * to (the one resolver's answer: the hospital named, else the only one
     * held). Null for a super-admin in global view or an actor with no
     * hospital — the row is then global, which is the truth.
     *
     * <p>This runs in {@code afterCompletion}, with no persistence context:
     * only the assignment's own columns and its EAGER role may be read here.
     * The LAZY hospital is a dead proxy; the audit service derives the hospital
     * name from {@code assignmentId} under its own transaction.</p>
     */
    private UserRoleHospitalAssignment resolveAssignment(UUID actorId) {
        UUID hospitalId = ActingScopeResolver.pinnedHospitalIdOrNull();
        UserRoleHospitalAssignmentRepository repository = assignmentRepositoryProvider.getIfAvailable();
        if (hospitalId == null || repository == null) {
            return null;
        }
        return repository.findFirstByUser_IdAndHospital_IdAndActiveTrue(actorId, hospitalId).orElse(null);
    }

    static AuditEventType eventTypeFor(String method, WriteAudited annotation) {
        if (annotation != null && annotation.eventType().length > 0) {
            return annotation.eventType()[0];
        }
        return eventTypeFor(method);
    }

    /**
     * QA D429: a create has no {@code {id}} or {@code {patientId}} in its
     * route, so its row named neither the record nor the patient. The
     * handler calls this with what it just created; the row then carries
     * both. Ids only. Outside a request (a direct call in a unit test, a
     * job) there is nothing to annotate and it does nothing.
     */
    public static void describeWrite(String resourceId, String patientId) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }
        if (resourceId != null) {
            attributes.setAttribute(RESOURCE_ID_ATTRIBUTE, resourceId, RequestAttributes.SCOPE_REQUEST);
        }
        if (patientId != null) {
            attributes.setAttribute(PATIENT_ID_ATTRIBUTE, patientId, RequestAttributes.SCOPE_REQUEST);
        }
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
        UUID declared = parseUuid(fromPath != null ? fromPath : request.getParameter(name));
        return declared != null ? declared : attributeUuid(request, PATIENT_ID_ATTRIBUTE);
    }

    private static UUID attributeUuid(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value != null ? parseUuid(value.toString()) : null;
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

    /**
     * The assignment's role at this hospital in the same bare form as
     * {@link #primaryRole} and the read-side interceptor ({@code DOCTOR}, not
     * {@code ROLE_DOCTOR}), so one actor's rows group under one role name.
     */
    private static String roleNameOf(UserRoleHospitalAssignment assignment, Authentication auth) {
        // The role CODE, not its display name: names are mixed ("HOSPITAL_ADMIN",
        // "ROLE_SUPER_ADMIN", ...) and the read side files the code (QA D187).
        if (assignment == null || assignment.getRole() == null || assignment.getRole().getCode() == null) {
            return primaryRole(auth);
        }
        String code = assignment.getRole().getCode();
        return code.startsWith(ROLE_PREFIX) ? code.substring(ROLE_PREFIX.length()) : code;
    }

    private static String primaryRole(Authentication auth) {
        // getAuthorities() is non-null by contract (Spring Security 7 is
        // @NullMarked); the old null guard was dead code (Sonar S2583).
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith(ROLE_PREFIX))
            .map(a -> a.substring(ROLE_PREFIX.length()))
            .findFirst()
            .orElse(null);
    }
}
