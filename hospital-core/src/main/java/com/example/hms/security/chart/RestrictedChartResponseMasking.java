package com.example.hms.security.chart;

import com.example.hms.payload.dto.ApiResponseWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * QA D3863: no answer carries a restricted chart to a caller without a live
 * break-the-glass session, whichever controller built it.
 *
 * <p>For a caller the restriction applies to ({@link RestrictedChartGate#isSubject}),
 * while any chart is restricted at all, every JSON answer is read as a tree:
 * <ul>
 *   <li>a GET whose answer IS a record of a blocked patient (its
 *       {@code patientId} or {@code patient.id}) is refused, 403
 *       {@code CHART_RESTRICTED} (or 404 for a stranger), as the record's own
 *       endpoint would have been by {@link RestrictedChartGuardInterceptor};</li>
 *   <li>everywhere else (lists, boards, worklists, write answers) the records of
 *       a blocked patient are masked by {@link RestrictedChartMasker}: the row
 *       stays, with who, where, when and status, and
 *       {@code "chartRestricted": true}.</li>
 * </ul>
 * A handler marked {@link RestrictedChartPolicy.Mode#MASK} is masked but
 * never refused; {@link RestrictedChartPolicy.Mode#NONE} is left alone.
 * {@code /me/**} is the caller's own record. While no chart is restricted the
 * cost is one indexed query per answer, cached for the request.
 *
 * <p>Ordered last, after the role shapings, which change the DTOs in place.
 * The collaborators arrive through {@link ObjectProvider}s: a
 * {@code @WebMvcTest} slice builds this advice without them and it does nothing.
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class RestrictedChartResponseMasking implements ResponseBodyAdvice<Object> {

    private final ObjectProvider<RestrictedChartGate> gateProvider;
    private final ObjectProvider<ObjectMapper> mapperProvider;

    public RestrictedChartResponseMasking(ObjectProvider<RestrictedChartGate> gateProvider,
                                          ObjectProvider<ObjectMapper> mapperProvider) {
        this.gateProvider = gateProvider;
        this.mapperProvider = mapperProvider;
    }

    @Override
    public boolean supports(@NonNull MethodParameter returnType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return AbstractJacksonHttpMessageConverter.class.isAssignableFrom(converterType)
            && policyOf(returnType) != RestrictedChartPolicy.Mode.NONE;
    }

    @Override
    public Object beforeBodyWrite(Object body, @NonNull MethodParameter returnType,
                                  @NonNull MediaType selectedContentType,
                                  @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  @NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response) {
        // An error answer (the refusal itself among them) is not a chart.
        if (body == null || body instanceof JsonNode || body instanceof CharSequence || body instanceof byte[]
            || returnType.hasMethodAnnotation(ExceptionHandler.class) || isOwnRecord(request)) {
            return body;
        }
        RestrictedChartGate gate = gateProvider.getIfAvailable();
        ObjectMapper mapper = mapperProvider.getIfAvailable();
        if (gate == null || mapper == null || !gate.isSubject() || !gate.anyRestricted()) {
            return body;
        }
        JsonNode tree = mapper.valueToTree(body);
        Set<UUID> candidates = new HashSet<>();
        RestrictedChartMasker.collectCandidateIds(tree, candidates);
        Set<UUID> blocked = gate.blockedAmong(candidates);
        if (blocked.isEmpty()) {
            return body;
        }
        if (request.getMethod() == HttpMethod.GET && policyOf(returnType) == null) {
            JsonNode record = singleRecord(body, tree);
            UUID owner = RestrictedChartMasker.patientOf(record);
            // The patient's own record is their identity, shaped below, never refused.
            if (owner != null && blocked.contains(owner) && !RestrictedChartMasker.isIdentityOf(record, blocked)) {
                throw gate.refusal(owner);
            }
        }
        RestrictedChartMasker.mask(tree, blocked);
        return tree;
    }

    /**
     * The record a single-record answer is, or {@code null} for a list:
     * through {@link ApiResponseWrapper}'s {@code data}.
     */
    private static JsonNode singleRecord(Object body, JsonNode tree) {
        Object payload = body;
        JsonNode node = tree;
        if (body instanceof ApiResponseWrapper<?> wrapper) {
            payload = wrapper.getData();
            node = tree.get("data");
        }
        if (payload == null || payload instanceof Collection<?> || payload instanceof Page<?>
            || payload instanceof Map<?, ?> || payload.getClass().isArray()) {
            return null;
        }
        return node;
    }

    static RestrictedChartPolicy.Mode policyOf(MethodParameter returnType) {
        Method method = returnType.getMethod();
        RestrictedChartPolicy own = method == null ? null
            : AnnotatedElementUtils.findMergedAnnotation(method, RestrictedChartPolicy.class);
        RestrictedChartPolicy policy = own != null ? own
            : AnnotatedElementUtils.findMergedAnnotation(returnType.getContainingClass(), RestrictedChartPolicy.class);
        return policy != null ? policy.value() : null;
    }

    private static boolean isOwnRecord(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            Object pattern = servlet.getServletRequest().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern != null) {
                return pattern.toString().startsWith("/me/");
            }
        }
        String path = request.getURI().getPath();
        return path != null && (path.startsWith("/me/") || path.startsWith("/api/me/"));
    }
}
