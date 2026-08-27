package com.example.hms.security.audit;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.AuditEventLogService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records that a clinician read a patient's record.
 *
 * <p><b>The gap this closes.</b> The patient portal offers a page answering
 * "what happened to my record". It was built on {@code PATIENT_ACCESS} audit
 * events, and those had four emitters in the entire codebase — none of them on
 * the path a chart open actually takes. Forty controllers serve patient-scoped
 * reads; one referenced the audit logger, for consent writes. So the page
 * listed break-glass sessions, shares and exports, and showed nothing at all
 * for the ordinary clinical access that makes up virtually every read of a
 * record.
 *
 * <p><b>Why an interceptor rather than a call per endpoint.</b> Hand-written
 * emission is how the gap formed. Every audit event in this codebase is an
 * explicit call, and the endpoints written after the convention was set are
 * exactly the ones that forgot it — {@code CrossTenantReadAudit} even
 * documents "the audit aspect that auto-records writes", which does not exist.
 * A central hook is the only version of this that stays true as controllers
 * are added, because the new controller is covered before anyone thinks about
 * it.
 *
 * <p><b>What is deliberately not recorded.</b>
 *
 * <ul>
 *   <li><b>Failed reads.</b> A 403 or a 404 disclosed nothing. Recording them
 *       would put attempts on a page the patient reads as disclosures.</li>
 *   <li><b>Writes.</b> This answers "who looked", and a write already leaves
 *       its own trail in the record it changed.</li>
 *   <li><b>Patients reading their own record.</b> Self-access is not a
 *       disclosure, and without this every visit to the disclosure page would
 *       append to the disclosure page.</li>
 * </ul>
 *
 * <p><b>Proxy access is a known limit.</b> A parent reading a child's record
 * holds {@code ROLE_PATIENT}, so it is skipped with the rest of self-access.
 * Whether a guardian's view belongs on a minor's disclosure log is a product
 * and legal question, not one to settle silently inside an interceptor — so
 * the current behaviour is the conservative one and this comment is the flag.
 *
 * <p>Emission is best-effort and never affects the response: it runs in
 * {@code afterCompletion}, the whole body is guarded, and
 * {@link AuditEventLogService#logEvent} is itself {@code REQUIRES_NEW} and
 * swallows persistence failures.
 */
@Slf4j
@Component
public class PatientAccessAuditInterceptor implements HandlerInterceptor {

    private static final String PATIENTS_PREFIX = "/patients/";
    private static final String PATIENT_ID_VAR = "patientId";
    private static final String ID_VAR = "id";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final String PATIENT_ROLE = "ROLE_PATIENT";

    /**
     * Resolved lazily rather than injected directly so that a {@code @WebMvcTest}
     * slice can build this interceptor without an {@link AuditEventLogService}
     * bean in its context. Slices scan {@code WebMvcConfigurer}s, so a hard
     * dependency here would fail every controller slice in the project — the
     * same trap {@code ReadOnlyModeFilter} hit, for the same reason.
     */
    private final ObjectProvider<AuditEventLogService> auditServiceProvider;

    /**
     * Owned outright rather than injected as a bean.
     *
     * <p>It has no collaborators — two numbers and a map — so a bean bought
     * nothing and cost the same slice breakage as the interceptor itself: nine
     * controller tests build this class and would then fail for want of a
     * {@code PatientAccessDedupe}. One fewer bean in the graph is one fewer
     * way for the audit hook to be quietly absent.
     */
    private final PatientAccessDedupe dedupe;

    @Value("${hms.audit.patient-access.enabled:true}")
    private boolean enabled;

    public PatientAccessAuditInterceptor(
            ObjectProvider<AuditEventLogService> auditServiceProvider,
            @Value("${hms.audit.patient-access.window-minutes:30}") long windowMinutes,
            @Value("${hms.audit.patient-access.max-tracked:50000}") int maxTracked) {
        this.auditServiceProvider = auditServiceProvider;
        this.dedupe = new PatientAccessDedupe(windowMinutes, maxTracked);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!enabled) {
            return;
        }
        try {
            recordIfPatientRead(request, response, handler, ex);
        } catch (Exception failure) {
            // The response has already been written. An audit problem must not
            // become the caller's problem, and it must not be silent either.
            log.warn("[PATIENT-ACCESS] Failed to record access for {} {}: {}",
                request.getMethod(), request.getRequestURI(), failure.getMessage(), failure);
        }
    }

    private void recordIfPatientRead(HttpServletRequest request, HttpServletResponse response,
                                     Object handler, Exception ex) {
        if (ex != null || !isSuccessfulRead(request, response)) {
            return;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        PatientAccessAudited annotation =
            handlerMethod.getMethodAnnotation(PatientAccessAudited.class);
        if (annotation != null && annotation.skip()) {
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails principal)) {
            return;
        }
        if (hasAuthority(auth, PATIENT_ROLE)) {
            return;
        }

        UUID patientId = resolvePatientId(request, annotation);
        if (patientId == null) {
            return;
        }

        UUID actorId = principal.getUserId();
        if (!dedupe.shouldRecord(actorId, patientId, System.currentTimeMillis())) {
            return;
        }

        AuditEventLogService auditService = auditServiceProvider.getIfAvailable();
        if (auditService == null) {
            return;
        }
        auditService.logEvent(AuditEventRequestDTO.builder()
            .eventType(AuditEventType.PATIENT_ACCESS)
            .status(AuditStatus.SUCCESS)
            // "PATIENT" is what DisclosureCategory.classify folds into
            // TREATMENT_ACCESS — "viewed for your care" on the patient's page.
            // Any other entity type here would land the row in the wrong
            // category or drop it from the counts entirely.
            .entityType("PATIENT")
            .patientId(patientId)
            .resourceId(patientId.toString())
            .userId(actorId)
            .userName(principal.getUsername())
            .roleName(primaryRole(auth))
            .ipAddress(request.getRemoteAddr())
            .eventDescription("Patient record viewed")
            .build());
    }

    private boolean isSuccessfulRead(HttpServletRequest request, HttpServletResponse response) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        int status = response.getStatus();
        return status >= 200 && status < 300;
    }

    /**
     * Convention first, annotation as the override.
     *
     * <p>{@code {id}} only counts under {@code /patients/**}. Elsewhere it is
     * the id of whatever that controller owns — an appointment, an invoice, a
     * ward — and reading it as a patient id would file the access against a
     * patient who has nothing to do with it. A wrong name on someone's
     * disclosure page is worse than an absent one.
     */
    private UUID resolvePatientId(HttpServletRequest request, PatientAccessAudited annotation) {
        Map<String, String> pathVariables = uriTemplateVariables(request);

        if (annotation != null && !annotation.value().isBlank()) {
            String name = annotation.value();
            String fromPath = pathVariables.get(name);
            return parseUuid(fromPath != null ? fromPath : request.getParameter(name));
        }

        String byConvention = pathVariables.get(PATIENT_ID_VAR);
        if (byConvention != null) {
            return parseUuid(byConvention);
        }

        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null && pattern.toString().startsWith(PATIENTS_PREFIX)) {
            return parseUuid(pathVariables.get(ID_VAR));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> uriTemplateVariables(HttpServletRequest request) {
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
            // Plenty of these paths accept a code, an MRN or a slug. Not a
            // patient id we can attribute, so not an access we can record.
            return null;
        }
    }

    private static boolean hasAuthority(Authentication auth, String authority) {
        return auth.getAuthorities() != null
            && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
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
