package com.example.hms.security.provider;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.FacilityType;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.provider.confinement.ProviderConfinement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The work behind {@link ProviderFacilityConfinementFilter} (provider plan
 * §3.3, §6.4): who is confined, which handler a request reaches, and the
 * refusal.
 *
 * <ul>
 *   <li><b>Who.</b> Any caller who is not a verified super-admin and whose
 *       live permitted set contains a provider facility (PHARMACY, LABORATORY),
 *       pinned to it or not. One facility-type query per request, over that
 *       set.</li>
 *   <li><b>What.</b> The handler Spring MVC itself would dispatch to, asked of
 *       the same {@code requestMappingHandlerMapping} before the request gets
 *       there; its method and exact pattern are matched against
 *       {@link ProviderConfinement}. Not a prefix of the raw URI, so a path
 *       that only looks allowed cannot land on another handler.</li>
 *   <li><b>Refusal.</b> The answer of an unmapped path, produced by the same
 *       exception resolvers from the same exception, so the two cannot be told
 *       apart (a 403 would confirm the endpoint exists).</li>
 * </ul>
 *
 * <p>A plain component, not a {@code Filter}: {@code @WebMvcTest} slices scan
 * filters, and the filter reaches this through an {@link ObjectProvider}.
 */
@Slf4j
@Component
public class ProviderConfinementPolicy {

    private final HospitalRepository hospitalRepository;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
    private final ObjectProvider<HandlerExceptionResolver> exceptionResolverProvider;

    public ProviderConfinementPolicy(
            HospitalRepository hospitalRepository,
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            @Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> exceptionResolverProvider) {
        this.hospitalRepository = hospitalRepository;
        this.handlerMappingProvider = handlerMappingProvider;
        this.exceptionResolverProvider = exceptionResolverProvider;
    }

    /**
     * The provider facility types in the caller's permitted set; empty when the
     * caller is not confined (no context, a verified super-admin, or hospitals only).
     */
    public Set<FacilityType> providerTypes(HospitalContext context) {
        if (context == null || context.isSuperAdmin()) {
            return Collections.emptySet();
        }
        Set<java.util.UUID> permitted = context.getPermittedHospitalIds();
        if (permitted == null || permitted.isEmpty()) {
            return Collections.emptySet();
        }
        Set<FacilityType> types = EnumSet.noneOf(FacilityType.class);
        for (FacilityType type : hospitalRepository.findProviderFacilityTypesByIdIn(permitted)) {
            if (type != null && type.isProvider()) {
                types.add(type);
            }
        }
        return types;
    }

    /** The caller holds a live PATIENT assignment, global or bound to a hospital. */
    public static boolean isPatientHolder(HospitalContext context) {
        return context != null && context.getAssignedRoles() != null
            && context.getAssignedRoles().contains(SecurityConstants.ROLE_PATIENT);
    }

    /** May this confined caller's request go on? */
    public boolean allows(HttpServletRequest request, Set<FacilityType> providerTypes, boolean patientHolder) {
        String handlerPattern = matchedHandlerPattern(request);
        if (handlerPattern != null) {
            return ProviderConfinement.allows(providerTypes, patientHolder, request.getMethod(), handlerPattern);
        }
        return ProviderConfinement.allowsNonMvc(request.getMethod(), pathWithinApplication(request));
    }

    /**
     * Answer exactly as an unmapped path: the exception Spring MVC raises for
     * one, through the same resolvers. The log names neither the caller nor
     * anything about a patient.
     */
    public void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.debug("[CONFINEMENT] Provider request outside the allow-list: {} refused as not found", request.getMethod());
        String path = pathWithinApplication(request);
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        NoResourceFoundException notFound = new NoResourceFoundException(method, path,
            path.startsWith("/") ? path.substring(1) : path);
        HandlerExceptionResolver resolver = exceptionResolverProvider.getIfAvailable();
        ModelAndView answered = null;
        if (resolver != null) {
            try {
                answered = resolver.resolveException(request, response, null, notFound);
            } catch (RuntimeException resolverFailure) {
                log.debug("[CONFINEMENT] Resolver failed ({}); answering a bare 404",
                    resolverFailure.getClass().getSimpleName());
            }
        }
        if (answered == null && !response.isCommitted()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /**
     * The pattern of the handler Spring MVC would dispatch this request to, or
     * {@code null} when none matches (an unmapped path, a wrong method, a
     * servlet outside MVC). The lookup leaves the request exactly as it found
     * it: every attribute the mapping sets is removed, every one it replaced
     * is restored, so the real dispatch starts clean.
     */
    String matchedHandlerPattern(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = handlerMappingProvider.getIfAvailable();
        if (mapping == null) {
            return null;
        }
        Map<String, Object> before = attributes(request);
        try {
            ServletRequestPathUtils.parseAndCache(request);
            HandlerExecutionChain chain = mapping.getHandler(request);
            if (chain == null) {
                return null;
            }
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            return pattern instanceof String matched ? matched : null;
        } catch (Exception noMatch) {
            // A path that matches but not with this method, media type or
            // parameters: not a handler this caller reaches.
            return null;
        } finally {
            restore(request, before);
        }
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        try {
            return RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication().value();
        } catch (RuntimeException unparsable) {
            return "";
        }
    }

    private static Map<String, Object> attributes(HttpServletRequest request) {
        Map<String, Object> snapshot = new HashMap<>();
        for (String name : Collections.list(request.getAttributeNames())) {
            snapshot.put(name, request.getAttribute(name));
        }
        return snapshot;
    }

    private static void restore(HttpServletRequest request, Map<String, Object> before) {
        for (String name : Collections.list(request.getAttributeNames())) {
            if (!before.containsKey(name)) {
                request.removeAttribute(name);
            }
        }
        before.forEach((name, value) -> {
            if (!Objects.equals(request.getAttribute(name), value)) {
                request.setAttribute(name, value);
            }
        });
    }
}
