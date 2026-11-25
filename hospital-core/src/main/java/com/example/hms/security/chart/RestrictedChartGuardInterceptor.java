package com.example.hms.security.chart;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D3863: a GET that names a restricted chart, or a record of it, is refused
 * before the handler runs unless the caller holds a live break-the-glass
 * session ({@link RestrictedChartGate}).
 *
 * <p>The patient is read off the request by {@link ChartKeys}: a
 * {@code patientId} path variable or request parameter, {@code {id}} under
 * {@code /patients/}, and the visit, prescription and imaging keys, resolved
 * to their patient. No path list: every handler is a chart read unless it
 * says otherwise with {@link RestrictedChartPolicy}, so a new endpoint is
 * covered the day it is written. {@code /me/**} is the caller's own record.
 *
 * <p>Writes are not refused here: the restriction is about who reads the
 * chart, and their answers are masked by {@link RestrictedChartResponseMasking}.
 *
 * <p>The gate arrives through an {@link ObjectProvider}: {@code @WebMvcTest}
 * slices build {@code WebMvcConfigurer}s without the services behind them
 * (the trap {@code PatientAccessAuditConfig} documents).
 */
@Component
public class RestrictedChartGuardInterceptor implements HandlerInterceptor {

    private static final Set<String> OWN_RECORD_PREFIXES = Set.of("/me/");

    private final ObjectProvider<RestrictedChartGate> gateProvider;

    public RestrictedChartGuardInterceptor(ObjectProvider<RestrictedChartGate> gateProvider) {
        this.gateProvider = gateProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !isRead(request) || policyOf(method) != null) {
            return true;
        }
        String pattern = patternOf(request);
        if (pattern != null && OWN_RECORD_PREFIXES.stream().anyMatch(pattern::startsWith)) {
            return true;
        }
        RestrictedChartGate gate = gateProvider.getIfAvailable();
        if (gate == null || !gate.isSubject()) {
            return true;
        }
        for (UUID patientId : patientsNamed(request, pattern, gate)) {
            gate.requireReadable(patientId);
        }
        return true;
    }

    /** The handler's own policy, else its controller's; {@code null} for a chart read. */
    static RestrictedChartPolicy policyOf(HandlerMethod method) {
        RestrictedChartPolicy own = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(),
            RestrictedChartPolicy.class);
        return own != null ? own
            : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RestrictedChartPolicy.class);
    }

    private static boolean isRead(HttpServletRequest request) {
        String verb = request.getMethod();
        return "GET".equalsIgnoreCase(verb) || "HEAD".equalsIgnoreCase(verb);
    }

    private static Set<UUID> patientsNamed(HttpServletRequest request, String pattern, RestrictedChartGate gate) {
        Set<UUID> patients = new LinkedHashSet<>();
        for (Map.Entry<String, String> variable : pathVariables(request).entrySet()) {
            Optional<ChartKeys.Owner> owner = ChartKeys.ofPathVariable(pattern, variable.getKey());
            UUID id = parse(variable.getValue());
            if (owner.isPresent() && id != null) {
                gate.ownerOf(owner.get(), id).ifPresent(patients::add);
            }
        }
        for (String name : ChartKeys.PARAMETERS) {
            String[] values = request.getParameterValues(name);
            if (values == null) {
                continue;
            }
            for (String value : values) {
                UUID id = parse(value);
                if (id != null) {
                    gate.ownerOf(ChartKeys.BY_NAME.get(name), id).ifPresent(patients::add);
                }
            }
        }
        return patients;
    }

    private static String patternOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return attribute instanceof Map ? (Map<String, String>) attribute : Map.of();
    }

    private static UUID parse(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(candidate.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
