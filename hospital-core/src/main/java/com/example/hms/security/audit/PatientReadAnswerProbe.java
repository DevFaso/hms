package com.example.hms.security.audit;

import com.example.hms.payload.dto.ApiResponseWrapper;
import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Tells {@link PatientAccessAuditInterceptor} when a read answered with
 * nothing, so the patient's « Qui a consulté mon dossier » lists the reads
 * that returned data and no other.
 *
 * <p>The interceptor sees the status and never the body, and a list endpoint
 * answers a reader who has nothing to read with {@code 200} and an empty
 * list, an empty page or {@code null}. Such an answer showed nothing of the
 * record: it is not a consultation. This advice looks at the answer as the
 * handler built it (after the role shapings, which may empty it) and marks
 * the request; the interceptor then writes no row, and counts no
 * break-the-glass read.
 *
 * <p>Empty means: no body, an empty collection, array or map, a page with
 * no row at all (a page past the end that still counts rows is an answer),
 * a slice without content, an empty {@link Optional}, an empty JSON container,
 * or an {@link ApiResponseWrapper} around one of these. Anything else,
 * a record, a count, a text, is an answer. No collaborators, so a
 * {@code @WebMvcTest} slice builds it as it is.
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class PatientReadAnswerProbe implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(@NonNull MethodParameter returnType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, @NonNull MethodParameter returnType,
                                  @NonNull MediaType selectedContentType,
                                  @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  @NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest raw = servletRequest.getServletRequest();
            if ("GET".equalsIgnoreCase(raw.getMethod()) && returnsNothing(body)) {
                raw.setAttribute(PatientAccessAuditInterceptor.NOTHING_RETURNED_ATTRIBUTE, Boolean.TRUE);
            }
        }
        return body;
    }

    /** Whether an answer carries nothing of a record. */
    static boolean returnsNothing(Object body) {
        if (body == null) {
            return true;
        }
        if (body instanceof ApiResponseWrapper<?> wrapper) {
            return returnsNothing(wrapper.getData());
        }
        if (body instanceof Optional<?> optional) {
            return optional.isEmpty() || returnsNothing(optional.get());
        }
        if (body instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (body instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (body instanceof Page<?> page) {
            // A page past the end still tells how many rows there are: a count is an answer.
            return !page.hasContent() && page.getTotalElements() == 0;
        }
        if (body instanceof Slice<?> slice) {
            return !slice.hasContent();
        }
        if (body instanceof JsonNode node) {
            return node.isNull() || node.isMissingNode() || ((node.isArray() || node.isObject()) && node.isEmpty());
        }
        return body.getClass().isArray() && Array.getLength(body) == 0;
    }
}
