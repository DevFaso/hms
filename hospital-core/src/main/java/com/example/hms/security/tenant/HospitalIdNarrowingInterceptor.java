package com.example.hms.security.tenant;

import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.security.context.HospitalContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

/**
 * Design §3.4 step 2, for every handler at once: a controller method that
 * receives a hospital id as a {@code hospitalId} path variable or request
 * parameter acts at that hospital, so the request's scope is narrowed to it
 * ({@link ActingScopeResolver#narrowTo}) BEFORE the handler runs and before
 * any other consumer can seal the scope.
 *
 * <p>So a super-admin's {@code ?hospitalId=B} is filtered and audited as B, a
 * multi-hospital caller's {@code ?hospitalId=B} is served at B, and a hospital
 * the caller may not act at is refused with 403 (audited as
 * {@code NOT_PERMITTED} or {@code NO_LONGER_PERMITTED}) instead of reaching a
 * service that may or may not check it. {@code resolveHospitalScope} naming
 * the same hospital afterwards is a no-op.
 *
 * <p>Not narrowed: a handler marked {@link HospitalScopeExempt} (its hospital
 * id is not its acting scope, with the reason on the annotation); a request
 * with no tenant context at all (no auth filter built one — a partner API key,
 * a test without the filter chain); a value that is not a UUID (binding
 * answers 400 for it). A hospital id in a request BODY is the controller's to
 * pass to {@code resolveHospitalScope}.
 */
@Component
public class HospitalIdNarrowingInterceptor implements HandlerInterceptor {

    static final String HOSPITAL_ID = "hospitalId";

    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    /**
     * A provider, not the bean: {@code @WebMvcTest} slices build every
     * {@code HandlerInterceptor} they find and would otherwise need the resolver.
     */
    private final ObjectProvider<ActingScopeResolver> resolverProvider;

    public HospitalIdNarrowingInterceptor(ObjectProvider<ActingScopeResolver> resolverProvider) {
        this.resolverProvider = resolverProvider;
    }

    /**
     * Always true: a refused hospital is answered by throwing
     * {@link HospitalScopeRefusedException} (403 through the exception
     * handler), never by a silent {@code false}.
     */
    @Override
    @SuppressWarnings("java:S3516")
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!(handler instanceof HandlerMethod method) || HospitalContextHolder.getContext().isEmpty()
            || isExempt(method)) {
            return true;
        }
        UUID hospitalId = requestedHospitalId(method, request);
        ActingScopeResolver resolver = resolverProvider.getIfAvailable();
        if (hospitalId == null || resolver == null) {
            return true;
        }
        if (resolver.narrowTo(hospitalId) instanceof ActingScope.Refused(ActingScope.Reason reason)) {
            throw new HospitalScopeRefusedException(reason, ActingScopeResolver.refusalMessage(reason));
        }
        return true;
    }

    /** True when the handler method or its class carries {@link HospitalScopeExempt}. */
    static boolean isExempt(HandlerMethod method) {
        return method.hasMethodAnnotation(HospitalScopeExempt.class)
            || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), HospitalScopeExempt.class);
    }

    /** The {@code hospitalId} path variable or request parameter the handler declares, if the request carries one. */
    static UUID requestedHospitalId(HandlerMethod method, HttpServletRequest request) {
        for (MethodParameter parameter : method.getMethodParameters()) {
            parameter.initParameterNameDiscovery(PARAMETER_NAMES);
            PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
            if (pathVariable != null && HOSPITAL_ID.equals(name(pathVariable.name(), pathVariable.value(), parameter))) {
                return parse(pathVariable(request));
            }
            RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
            if (requestParam != null && HOSPITAL_ID.equals(name(requestParam.name(), requestParam.value(), parameter))) {
                return parse(request.getParameter(HOSPITAL_ID));
            }
        }
        return null;
    }

    /** The declared name ({@code name} or its alias {@code value}), else the compiled parameter name. */
    static String name(String declaredName, String declaredValue, MethodParameter parameter) {
        if (StringUtils.hasText(declaredName)) {
            return declaredName;
        }
        return StringUtils.hasText(declaredValue) ? declaredValue : parameter.getParameterName();
    }

    private static String pathVariable(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map && map.get(HOSPITAL_ID) instanceof String value ? value : null;
    }

    private static UUID parse(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
