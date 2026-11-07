package com.example.hms.controller.support;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Collection;
import java.util.List;

/**
 * QA D1429, coordinator's decision: why the desk overbooked a visit is free text written by staff for
 * staff, and it reaches neither a patient nor a proxy. One place, on the way out, because a patient reads
 * appointments through {@code /me/patient/**} (their own and, as a proxy, someone else's) and through the
 * {@code /appointments} reads that admit ROLE_PATIENT.
 *
 * <p>On the portal paths for everybody, since a staff member can be a proxy; elsewhere for a caller who is
 * nothing but a patient. The DTOs are built per request, so they are changed in place.
 */
@ControllerAdvice
public class PatientResponseShaping implements ResponseBodyAdvice<Object> {

    private static final String PATIENT = "ROLE_PATIENT";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final List<String> PATIENT_PATH_PREFIXES = List.of("/me/patient/", "/api/me/patient/");

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
        if (body != null && !(body instanceof byte[]) && (isPatientPath(request) || isPatientOnly())) {
            shape(body);
        }
        return body;
    }

    private static boolean isPatientPath(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        return path != null && PATIENT_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static boolean isPatientOnly() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        List<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(authority -> authority != null && authority.startsWith(ROLE_PREFIX))
            .toList();
        return roles.contains(PATIENT) && roles.stream().allMatch(PATIENT::equals);
    }

    /** Removes staff-only text from one response body, through its wrappers. */
    static void shape(Object body) {
        switch (body) {
            case Page<?> page -> page.getContent().forEach(PatientResponseShaping::shapeIfPresent);
            case Collection<?> many -> many.forEach(PatientResponseShaping::shapeIfPresent);
            case ApiResponseWrapper<?> wrapper -> shapeIfPresent(wrapper.getData());
            case AppointmentResponseDTO appointment -> appointment.setOverbookReason(null);
            default -> { /* No staff-only field: PatientResponseShapingTest keeps the holders known. */ }
        }
    }

    private static void shapeIfPresent(Object body) {
        if (body != null) {
            shape(body);
        }
    }
}
