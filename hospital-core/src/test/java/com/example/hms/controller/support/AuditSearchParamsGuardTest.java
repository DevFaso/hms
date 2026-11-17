package com.example.hms.controller.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D798 then D801: the audit event-type filter was silently ignored twice,
 * each time on a different endpoint, because each endpoint bound its own
 * spelling ({@code eventTypes} on the super-admin search, {@code eventType}
 * on {@code /audit-logs}) and Spring drops a parameter nothing binds. Three
 * rules keep that class of bug closed as audit endpoints are added:
 *
 * <ol>
 *   <li>every GET handler under an audit path is {@link StrictQueryParams},
 *       so an undeclared parameter is a 400, never ignored;</li>
 *   <li>a handler that takes one of {@code eventType} / {@code eventTypes}
 *       takes both;</li>
 *   <li>both are bound as {@code List<String>} and read by
 *       {@link AuditEventTypeParams}, so every endpoint accepts the same
 *       spellings and refuses an unknown value with the same translated 400
 *       (an enum-typed binding answered a different, generic message).</li>
 * </ol>
 */
@DisplayName("Audit search parameters")
class AuditSearchParamsGuardTest {

    private static final String CONTROLLER_PACKAGE = "com.example.hms.controller";
    private static final Set<String> EVENT_TYPE_NAMES = Set.of("eventType", "eventTypes");

    /**
     * Audit reads allowed to stay lax, each with its reason. This list may
     * only shrink.
     */
    private static final Map<String, String> LAX_ALLOWED = Map.of(
        "ProviderAdminController#auditTrail",
        "reads page/size only once the caller is known to be the facility's admin and answers 404 "
            + "otherwise (provider confinement): a 400 before that check would reveal the endpoint");

    @Test
    @DisplayName("every audit GET handler refuses parameters it does not declare")
    void everyAuditReadIsStrict() {
        List<Method> auditReads = auditGetHandlers();
        assertThat(auditReads).as("the scan found the audit read endpoints").hasSizeGreaterThanOrEqualTo(13);
        Set<String> lax = new TreeSet<>();
        for (Method method : auditReads) {
            boolean strict = AnnotatedElementUtils.hasAnnotation(method, StrictQueryParams.class)
                || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), StrictQueryParams.class);
            String key = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
            if (strict) {
                assertThat(LAX_ALLOWED).as("%s is strict now: remove its allowance", key).doesNotContainKey(key);
            } else if (!LAX_ALLOWED.containsKey(key)) {
                lax.add(key);
            }
        }
        assertThat(lax).withFailMessage("Audit reads that silently ignore unknown parameters: %s", lax).isEmpty();
    }

    @Test
    @DisplayName("eventType and eventTypes come together, as List<String>, on every handler")
    void theEventTypeFilterIsTheSameEverywhere() {
        Set<String> problems = new TreeSet<>();
        int filtered = 0;
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                Set<String> names = new TreeSet<>();
                for (int i = 0; i < method.getParameterCount(); i++) {
                    MethodParameter parameter = new MethodParameter(method, i);
                    parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
                    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
                    if (requestParam == null) {
                        continue;
                    }
                    String name = nameOf(requestParam, parameter);
                    if (!EVENT_TYPE_NAMES.contains(name)) {
                        continue;
                    }
                    names.add(name);
                    if (!isListOfString(method, i)) {
                        problems.add(controller.getSimpleName() + "#" + method.getName() + " binds " + name
                            + " as " + method.getGenericParameterTypes()[i].getTypeName() + ", not List<String>");
                    }
                }
                if (names.isEmpty()) {
                    continue;
                }
                filtered++;
                if (!names.equals(EVENT_TYPE_NAMES)) {
                    problems.add(controller.getSimpleName() + "#" + method.getName() + " takes only " + names);
                }
            }
        }
        assertThat(filtered).as("the scan found the event-type filters").isGreaterThanOrEqualTo(4);
        assertThat(problems).withFailMessage("Inconsistent audit event-type filters: %s", problems).isEmpty();
    }

    private static String nameOf(RequestParam requestParam, MethodParameter parameter) {
        if (StringUtils.hasText(requestParam.name())) {
            return requestParam.name();
        }
        return StringUtils.hasText(requestParam.value()) ? requestParam.value() : parameter.getParameterName();
    }

    private static boolean isListOfString(Method method, int index) {
        if (!(method.getGenericParameterTypes()[index] instanceof ParameterizedType type)) {
            return false;
        }
        return type.getRawType() == List.class && type.getActualTypeArguments()[0] == String.class;
    }

    private static List<Method> auditGetHandlers() {
        List<Method> handlers = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            boolean auditController = mapping != null && Arrays.stream(mapping.path())
                .anyMatch(path -> path.toLowerCase(Locale.ROOT).contains("audit"));
            for (Method method : controller.getDeclaredMethods()) {
                GetMapping get = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);
                if (get == null) {
                    continue;
                }
                boolean auditPath = auditController || Arrays.stream(get.path())
                    .anyMatch(path -> path.toLowerCase(Locale.ROOT).contains("audit"));
                if (auditPath) {
                    handlers.add(method);
                }
            }
        }
        return handlers;
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            try {
                classes.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return classes;
    }
}
