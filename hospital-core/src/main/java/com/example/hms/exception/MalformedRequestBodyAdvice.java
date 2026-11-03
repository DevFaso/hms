package com.example.hms.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.validation.annotation.ValidationAnnotationUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * QA D1349 (round 30): one answer names every malformed value of a request
 * body AND every field its bean validation refuses.
 *
 * <p>The collector of {@link RequestBodyProblems} is opened just before a
 * {@code @RequestBody} is read and closed just after. When the read recorded
 * a malformed value (read as absent by {@code RequestBodyDefaultsModule}),
 * the body is validated here exactly as Spring would (the same {@code @Valid}
 * / {@code @Validated} groups; each element of a list body) and the request
 * is refused with {@link MalformedRequestBodyException}: such a body never
 * reaches the controller, valid or not. A body read without a malformed value
 * passes unchanged and Spring validates it as before.
 *
 * <p>No service dependency (it is in every {@code @WebMvcTest} slice); the
 * validator is optional, without it only the malformed values are named.
 */
@ControllerAdvice
public class MalformedRequestBodyAdvice extends RequestBodyAdviceAdapter {

    private final ObjectProvider<Validator> validator;

    public MalformedRequestBodyAdvice(ObjectProvider<Validator> validator) {
        this.validator = validator;
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) {
        RequestBodyProblems.begin();
        return inputMessage;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        List<String> malformed = RequestBodyProblems.finish();
        if (malformed.isEmpty()) {
            return body;
        }
        throw new MalformedRequestBodyException(malformed, violations(body, parameter));
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                  Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        RequestBodyProblems.finish();
        return body;
    }

    private List<MalformedRequestBodyException.BodyViolation> violations(Object body, MethodParameter parameter) {
        Optional<Class<?>[]> validated = validationGroups(parameter);
        Validator v = validator.getIfAvailable();
        List<MalformedRequestBodyException.BodyViolation> out = new ArrayList<>();
        if (validated.isEmpty() || v == null || body == null) {
            return out;
        }
        Class<?>[] groups = validated.get();
        if (body instanceof Collection<?> elements) {
            int index = 0;
            for (Object element : elements) {
                addViolations(out, v, element, "[" + index + "]", groups);
                index++;
            }
        } else {
            addViolations(out, v, body, "", groups);
        }
        return out;
    }

    private static void addViolations(List<MalformedRequestBodyException.BodyViolation> out, Validator v,
                                      Object bean, String prefix, Class<?>[] groups) {
        if (bean == null) {
            return;
        }
        // Sorted by path: a validator answers a set, the response is stable.
        List<ConstraintViolation<Object>> sorted = new ArrayList<>(v.validate(bean, groups));
        sorted.sort(Comparator.comparing(violation -> violation.getPropertyPath().toString()));
        for (ConstraintViolation<Object> violation : sorted) {
            out.add(new MalformedRequestBodyException.BodyViolation(prefix, bean.getClass(), violation));
        }
    }

    /** The groups Spring would validate the parameter with; empty when it is not validated. */
    private static Optional<Class<?>[]> validationGroups(MethodParameter parameter) {
        for (Annotation annotation : parameter.getParameterAnnotations()) {
            Object[] hints = ValidationAnnotationUtils.determineValidationHints(annotation);
            if (hints != null) {
                return Optional.of(Arrays.stream(hints)
                    .filter(Class.class::isInstance)
                    .map(Class.class::cast)
                    .toArray(Class<?>[]::new));
            }
        }
        return Optional.empty();
    }
}
