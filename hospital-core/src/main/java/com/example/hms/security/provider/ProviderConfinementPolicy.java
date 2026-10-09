package com.example.hms.security.provider;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.FacilityType;
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
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
 *       live assignments include a provider facility (PHARMACY, LABORATORY),
 *       pinned to it or not. The types come with the live context
 *       ({@link HospitalContext#getProviderFacilityTypes()}), computed from the
 *       same assignment read as the permitted set: no query of their own.</li>
 *   <li><b>What.</b> The handler Spring MVC itself would dispatch to, asked of
 *       the same {@code requestMappingHandlerMapping} before the request gets
 *       there; its method and exact pattern are matched against
 *       {@link ProviderConfinement}. Not a prefix of the raw URI, so a path
 *       that only looks allowed cannot land on another handler.</li>
 *   <li><b>Refusal.</b> The answer of an unmapped path, produced by the same
 *       exception resolvers from the same exception, so the two cannot be told
 *       apart (a 403 would confirm the endpoint exists). A request that maps
 *       to an ALLOWED path but not with this method, media type or parameters
 *       is let through, so MVC answers its 405, 415, 406 or 400 as for anyone.</li>
 * </ul>
 *
 * <p>A plain component, not a {@code Filter}: {@code @WebMvcTest} slices scan
 * filters, and the filter reaches this through an {@link ObjectProvider}.
 */
@Slf4j
@Component
public class ProviderConfinementPolicy {

    private static final List<String> EVERY_METHOD = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
    private final ObjectProvider<HandlerExceptionResolver> exceptionResolverProvider;

    public ProviderConfinementPolicy(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            @Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> exceptionResolverProvider) {
        this.handlerMappingProvider = handlerMappingProvider;
        this.exceptionResolverProvider = exceptionResolverProvider;
    }

    /**
     * The provider facility types the caller is confined by; empty when the
     * caller is not confined (no context, a verified super-admin, or hospitals only).
     */
    public static Set<FacilityType> providerTypes(HospitalContext context) {
        if (context == null || context.isSuperAdmin() || context.getProviderFacilityTypes() == null) {
            return Collections.emptySet();
        }
        return context.getProviderFacilityTypes();
    }

    /** The caller holds a live PATIENT assignment, global or bound to a hospital. */
    public static boolean isPatientHolder(HospitalContext context) {
        return context != null && context.getAssignedRoles() != null
            && context.getAssignedRoles().contains(SecurityConstants.ROLE_PATIENT);
    }

    /** May this confined caller's request go on? Leaves the request as it found it. */
    public boolean allows(HttpServletRequest request, Set<FacilityType> providerTypes, boolean patientHolder) {
        RequestMappingHandlerMapping mapping = handlerMappingProvider.getIfAvailable();
        if (mapping == null) {
            return ProviderConfinement.allowsNonMvc(request.getMethod(), pathWithinApplication(request));
        }
        Map<String, Object> before = attributes(request);
        try {
            ServletRequestPathUtils.parseAndCache(request);
            HandlerExecutionChain chain = mapping.getHandler(request);
            if (chain == null) {
                return ProviderConfinement.allowsNonMvc(request.getMethod(), pathWithinApplication(request));
            }
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            return pattern instanceof String matched
                && ProviderConfinement.allows(providerTypes, patientHolder, request.getMethod(), matched);
        } catch (HttpRequestMethodNotSupportedException | HttpMediaTypeNotSupportedException
                 | HttpMediaTypeNotAcceptableException | UnsatisfiedServletRequestParameterException partialMatch) {
            // The path is mapped, but not with this method, media type or
            // parameters. On an allowed path MVC answers that (405, 415, 406,
            // 400) as it does for anyone; on any other path the caller gets
            // the unmapped answer.
            return pathMatchesAnAllowedHandler(mapping, request, providerTypes, patientHolder);
        } catch (Exception noMatch) {
            return false;
        } finally {
            restore(request, before);
        }
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
     * {@code null} when none matches. Leaves the request as it found it.
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
            Object pattern = chain == null ? null : request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            return pattern instanceof String matched ? matched : null;
        } catch (Exception noMatch) {
            return null;
        } finally {
            restore(request, before);
        }
    }

    /** Some handler whose pattern matches this path is one the caller may reach, with one of its methods. */
    private static boolean pathMatchesAnAllowedHandler(RequestMappingHandlerMapping mapping, HttpServletRequest request,
                                                       Set<FacilityType> providerTypes, boolean patientHolder) {
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            PathPatternsRequestCondition patterns = info.getPathPatternsCondition();
            PathPatternsRequestCondition matching = patterns == null ? null : patterns.getMatchingCondition(request);
            if (matching == null) {
                continue;
            }
            Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
            List<String> methods = declared.isEmpty()
                ? EVERY_METHOD
                : declared.stream().map(RequestMethod::name).toList();
            for (String pattern : matching.getPatternValues()) {
                for (String method : methods) {
                    if (ProviderConfinement.allows(providerTypes, patientHolder, method, pattern)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
