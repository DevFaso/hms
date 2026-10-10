package com.example.hms.security.provider;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.FacilityType;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.provider.confinement.ProviderConfinement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
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
import org.springframework.web.util.pattern.PathPattern;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The work behind {@link ProviderFacilityConfinementFilter} (provider plan
 * §3.3, §6.4): who is confined, which handler a request reaches, and the
 * refusal.
 *
 * <ul>
 *   <li><b>Who.</b> Any caller who is not a verified super-admin and whose
 *       live assignments include a provider facility (PHARMACY, LABORATORY),
 *       pinned to it or not: {@link HospitalContext#getProviderFacilityTypes()},
 *       computed with the permitted set from the same assignment read.</li>
 *   <li><b>What.</b> The handler Spring MVC itself would dispatch to, asked of
 *       the same {@code requestMappingHandlerMapping} once; its method and exact
 *       pattern are matched against {@link ProviderConfinement}, with the
 *       own-id constraint where an entry carries one. Not a prefix of the raw
 *       URI, so a path that only looks allowed cannot land on another handler.</li>
 *   <li><b>Refusal.</b> The answer of an unmapped path, produced by the same
 *       exception resolvers from the same exception, so the two cannot be told
 *       apart. A request MVC would answer itself goes on only where that
 *       answer discloses nothing the caller may not reach: a 405 or an
 *       {@code OPTIONS} (whose {@code Allow} header lists every method the path
 *       maps) only when the caller may reach EVERY handler matching the path;
 *       a 415, 406 or 400 only when a handler the caller may reach (its method,
 *       the request's own, on the allow-list, and its own-id constraint met)
 *       matches the path. Anything else gets the unmapped answer.</li>
 * </ul>
 *
 * <p>A plain component, not a {@code Filter}: {@code @WebMvcTest} slices scan
 * filters, and the filter reaches this through an {@link ObjectProvider}.
 */
@Slf4j
@Component
public class ProviderConfinementPolicy implements SmartInitializingSingleton {

    private static final List<String> EVERY_METHOD = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /** One handler pattern of the application and the methods it answers. */
    private record HandlerPattern(PathPattern pattern, List<String> methods) {
    }

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
    private final ObjectProvider<HandlerExceptionResolver> exceptionResolverProvider;

    /** Every handler pattern, read once from the handler mapping (an immutable list, published atomically). */
    private final AtomicReference<List<HandlerPattern>> handlerPatterns = new AtomicReference<>();

    /** Per kind of caller (provider types, patient holder): the handler patterns some method of which is allowed. */
    private final Map<String, List<HandlerPattern>> allowedPatterns = new ConcurrentHashMap<>();

    public ProviderConfinementPolicy(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            @Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> exceptionResolverProvider) {
        this.handlerMappingProvider = handlerMappingProvider;
        this.exceptionResolverProvider = exceptionResolverProvider;
    }

    /** Index the handler patterns at start-up, once the handler mapping is ready. */
    @Override
    public void afterSingletonsInstantiated() {
        RequestMappingHandlerMapping mapping = handlerMappingProvider.getIfAvailable();
        if (mapping != null) {
            handlerPatterns(mapping);
        }
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

    /**
     * THE confinement rule, for every caller of it (the confinement filter,
     * the STOMP interceptor, the session bootstrap, the notification service):
     * the caller's live context holds a provider facility type and is not a
     * verified super-admin. A context that links no local account holds no
     * assignment, so it is not confined; where linkage matters, use
     * {@link #isLinkedAndUnconfined} or {@link #isUnconfinedStaff}.
     */
    public static boolean isConfined(HospitalContext context) {
        return !providerTypes(context).isEmpty();
    }

    /**
     * A caller linked to a local account and not confined: who may use a
     * channel a provider user may not (the STOMP broadcasts and SEND). A
     * caller that links no local account is refused there (fail closed).
     */
    public static boolean isLinkedAndUnconfined(HospitalContext context) {
        return context != null && context.getPrincipalUserId() != null && !isConfined(context);
    }

    /**
     * {@link #isLinkedAndUnconfined}, holding a live role other than PATIENT
     * (a verified super-admin included): staff, e.g. who may flip a
     * broadcast notification's shared read flag.
     */
    public static boolean isUnconfinedStaff(HospitalContext context) {
        if (!isLinkedAndUnconfined(context)) {
            return false;
        }
        Set<String> roles = context.getAssignedRoles();
        return roles != null && roles.stream().anyMatch(role -> !SecurityConstants.ROLE_PATIENT.equals(role));
    }

    /** The caller holds a live PATIENT assignment, global or bound to a hospital. */
    public static boolean isPatientHolder(HospitalContext context) {
        return context != null && context.getAssignedRoles() != null
            && context.getAssignedRoles().contains(SecurityConstants.ROLE_PATIENT);
    }

    /**
     * May this confined caller's request go on? One handler lookup; the
     * request is left exactly as it was found (every attribute the lookup
     * sets is removed, every one it replaced restored).
     */
    public boolean allows(HttpServletRequest request, HospitalContext context) {
        Set<FacilityType> providerTypes = providerTypes(context);
        boolean patientHolder = isPatientHolder(context);
        RequestMappingHandlerMapping mapping = handlerMappingProvider.getIfAvailable();
        if (mapping == null) {
            return ProviderConfinement.allowsNonMvc(request.getMethod(), pathWithinApplication(request));
        }
        UUID callerUserId = context == null ? null : context.getPrincipalUserId();
        Map<String, Object> before = attributes(request);
        try {
            ServletRequestPathUtils.parseAndCache(request);
            if (HttpMethod.OPTIONS.matches(request.getMethod())) {
                // MVC answers OPTIONS itself, with every method the path maps
                // in its Allow header: only where the caller may reach them all.
                return mayReachEveryHandlerOf(mapping, request, providerTypes, patientHolder, callerUserId);
            }
            HandlerExecutionChain chain = mapping.getHandler(request);
            if (chain == null) {
                return ProviderConfinement.allowsNonMvc(request.getMethod(), pathWithinApplication(request));
            }
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            return pattern instanceof String matched
                && ProviderConfinement.allowsRequest(providerTypes, patientHolder, request.getMethod(), matched,
                    uriVariables(request), callerUserId);
        } catch (HttpRequestMethodNotSupportedException wrongMethod) {
            // The path is mapped, not for this method: MVC's 405 lists every
            // method the path maps (Allow), so only where the caller may reach
            // every one of them; otherwise the unmapped answer.
            return mayReachEveryHandlerOf(mapping, request, providerTypes, patientHolder, callerUserId);
        } catch (HttpMediaTypeNotSupportedException | HttpMediaTypeNotAcceptableException
                 | UnsatisfiedServletRequestParameterException partialMatch) {
            // The path is mapped for this method, but not with this media type
            // or these parameters: MVC's 415, 406 or 400 only where a handler
            // the caller may reach, for this method, matches the path.
            return mayReachAHandlerFor(mapping, request, providerTypes, patientHolder, callerUserId);
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
     * 405 and {@code OPTIONS}: at least one handler matches the request's path
     * (already parsed), and the caller may reach EVERY method of EVERY one of
     * them, own-id constraint included, so the {@code Allow} header names
     * nothing hidden. Scans every handler pattern; only a confined caller's
     * wrong-method or {@code OPTIONS} request comes here.
     */
    private boolean mayReachEveryHandlerOf(RequestMappingHandlerMapping mapping, HttpServletRequest request,
                                           Set<FacilityType> providerTypes, boolean patientHolder,
                                           UUID callerUserId) {
        PathContainer path = ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication();
        boolean matched = false;
        for (HandlerPattern handler : handlerPatterns(mapping)) {
            PathPattern.PathMatchInfo match = handler.pattern().matchAndExtract(path);
            if (match == null) {
                continue;
            }
            for (String method : handler.methods()) {
                if (!ProviderConfinement.allowsRequest(providerTypes, patientHolder, method,
                        handler.pattern().getPatternString(), match.getUriVariables(), callerUserId)) {
                    return false;
                }
            }
            matched = true;
        }
        return matched;
    }

    /**
     * 415, 406 and 400: a handler the caller may reach (an allowed handler
     * mapped for the request's own method, its own-id constraint met) matches
     * the request's path (already parsed).
     */
    private boolean mayReachAHandlerFor(RequestMappingHandlerMapping mapping, HttpServletRequest request,
                                        Set<FacilityType> providerTypes, boolean patientHolder, UUID callerUserId) {
        PathContainer path = ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication();
        String method = HttpMethod.HEAD.matches(request.getMethod()) ? HttpMethod.GET.name() : request.getMethod();
        for (HandlerPattern handler : allowedPatterns(mapping, providerTypes, patientHolder)) {
            if (!handler.methods().contains(method)) {
                continue;
            }
            PathPattern.PathMatchInfo match = handler.pattern().matchAndExtract(path);
            if (match != null && ProviderConfinement.allowsRequest(providerTypes, patientHolder, method,
                    handler.pattern().getPatternString(), match.getUriVariables(), callerUserId)) {
                return true;
            }
        }
        return false;
    }

    /** The handlers some method of which this kind of caller may reach; computed once per kind. */
    private List<HandlerPattern> allowedPatterns(RequestMappingHandlerMapping mapping, Set<FacilityType> providerTypes,
                                                 boolean patientHolder) {
        String key = new TreeSet<>(providerTypes.stream().map(Enum::name).toList()) + "|" + patientHolder;
        return allowedPatterns.computeIfAbsent(key, k -> {
            List<HandlerPattern> allowed = new ArrayList<>();
            for (HandlerPattern handler : handlerPatterns(mapping)) {
                for (String method : handler.methods()) {
                    if (ProviderConfinement.allows(providerTypes, patientHolder, method,
                            handler.pattern().getPatternString())) {
                        allowed.add(handler);
                        break;
                    }
                }
            }
            return List.copyOf(allowed);
        });
    }

    private List<HandlerPattern> handlerPatterns(RequestMappingHandlerMapping mapping) {
        List<HandlerPattern> known = handlerPatterns.get();
        if (known == null) {
            List<HandlerPattern> read = new ArrayList<>();
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                PathPatternsRequestCondition patterns = info.getPathPatternsCondition();
                if (patterns == null) {
                    continue;
                }
                Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
                List<String> methods = declared.isEmpty()
                    ? EVERY_METHOD
                    : declared.stream().map(RequestMethod::name).toList();
                for (PathPattern pattern : patterns.getPatterns()) {
                    read.add(new HandlerPattern(pattern, methods));
                }
            }
            handlerPatterns.compareAndSet(null, List.copyOf(read));
            known = handlerPatterns.get();
        }
        return known;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> uriVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    /**
     * The request's path within the application (no context path), as Spring
     * parses it: the one computation this policy and the confinement filter
     * share. Empty when the URI cannot be parsed.
     */
    public static String pathWithinApplication(HttpServletRequest request) {
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
