package com.example.hms.controller.support;

import com.example.hms.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.BeanUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces {@link StrictQueryParams}: every query parameter must be one the
 * handler declares, or the request is refused with
 * {@code error.request.unknownParameter} (400, in the caller's language)
 * before the handler runs. The accepted names come from the handler's own
 * signature: each {@code @RequestParam} (its name, or the Java parameter
 * name), {@code page}/{@code size}/{@code sort} for a {@link Pageable},
 * {@code sort} for a {@link Sort}. A handler that takes a
 * {@code @RequestParam Map} accepts anything and is not checked.
 *
 * <p>No dependencies, so registering it does not disturb the
 * {@code @WebMvcTest} slices.
 */
public class StrictQueryParamsInterceptor implements HandlerInterceptor {

    /** Spring Data's default {@link Pageable} parameter names (no override is configured). */
    private static final Set<String> PAGEABLE_PARAMS = Set.of("page", "size", "sort");
    private static final String SORT_PARAM = "sort";
    /** A caller-supplied name is echoed in the message; a long one is cut. */
    private static final int MAX_ECHOED_NAME = 64;
    private static final ParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

    /** What one handler accepts: {@code anything} when it binds a whole {@code @RequestParam Map}. */
    record Accepted(boolean anything, Set<String> names) {
    }

    private final Map<Method, Accepted> acceptedByMethod = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !isStrict(method)) {
            return true;
        }
        Accepted accepted = acceptedByMethod.computeIfAbsent(method.getMethod(), m -> accepted(method));
        if (accepted.anything()) {
            return true;
        }
        Set<String> unknown = new TreeSet<>(request.getParameterMap().keySet());
        unknown.removeAll(accepted.names());
        if (!unknown.isEmpty()) {
            throw new BusinessException("error.request.unknownParameter",
                echoed(unknown.iterator().next()), String.join(", ", accepted.names()));
        }
        return true;
    }

    private static boolean isStrict(HandlerMethod method) {
        return AnnotatedElementUtils.hasAnnotation(method.getMethod(), StrictQueryParams.class)
            || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), StrictQueryParams.class);
    }

    /** The query parameter names {@code method} binds. */
    static Accepted accepted(HandlerMethod method) {
        Set<String> names = new TreeSet<>();
        for (MethodParameter parameter : method.getMethodParameters()) {
            Class<?> type = parameter.getParameterType();
            RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
            if (requestParam != null) {
                String name = StringUtils.hasText(requestParam.name()) ? requestParam.name() : requestParam.value();
                if (!StringUtils.hasText(name) && Map.class.isAssignableFrom(type)) {
                    return new Accepted(true, Set.of());
                }
                addName(names, StringUtils.hasText(name) ? name : parameterName(parameter));
            } else if (Pageable.class.isAssignableFrom(type)) {
                names.addAll(PAGEABLE_PARAMS);
            } else if (Sort.class.isAssignableFrom(type)) {
                names.add(SORT_PARAM);
            } else if (parameter.hasParameterAnnotation(ModelAttribute.class)) {
                for (PropertyDescriptor property : BeanUtils.getPropertyDescriptors(type)) {
                    if (property.getWriteMethod() != null) {
                        names.add(property.getName());
                    }
                }
            } else if (parameter.getParameterAnnotations().length == 0 && BeanUtils.isSimpleProperty(type)) {
                // An unannotated simple argument is bound from the query string by its name.
                addName(names, parameterName(parameter));
            }
        }
        return new Accepted(false, Collections.unmodifiableSet(names));
    }

    /**
     * The Java name of an argument bound by its name. Without {@code -parameters}
     * it is unknown, and Spring cannot bind that argument either (it fails the
     * request itself), so nothing is added for it here.
     */
    private static String parameterName(MethodParameter parameter) {
        parameter.initParameterNameDiscovery(NAMES);
        return parameter.getParameterName();
    }

    private static void addName(Set<String> names, String name) {
        if (name != null) {
            names.add(name);
        }
    }

    private static String echoed(String name) {
        return name.length() <= MAX_ECHOED_NAME ? name : name.substring(0, MAX_ECHOED_NAME) + "…";
    }
}
