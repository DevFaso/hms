package com.example.hms.fhir.smart;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import com.example.hms.fhir.smart.SmartConfigurationController.SmartConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * HAPI server interceptor that serves
 * {@code GET /api/fhir/.well-known/smart-configuration} directly without
 * dispatching the request to a FHIR resource provider.
 *
 * <p>This is the spec-conformant location for SMART-on-FHIR App Launch 1.0
 * discovery. Returning {@code false} from the hook signals HAPI to stop
 * processing the request — the response we wrote stands as-is.
 */
@Interceptor
@Component
public class SmartConfigurationInterceptor {

    private static final String SMART_PATH_SUFFIX = "/.well-known/smart-configuration";

    private final SmartConfigurationBuilder builder;
    private final ObjectMapper objectMapper;

    public SmartConfigurationInterceptor(SmartConfigurationBuilder builder, ObjectMapper objectMapper) {
        this.builder = builder;
        this.objectMapper = objectMapper;
    }

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
    public boolean handleSmartConfiguration(HttpServletRequest request, HttpServletResponse response) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return true;
        String requestUri = request.getRequestURI();
        if (requestUri == null || !requestUri.endsWith(SMART_PATH_SUFFIX)) return true;

        SmartConfiguration cfg = builder.build(request);
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            try (PrintWriter writer = response.getWriter()) {
                writer.write(objectMapper.writeValueAsString(cfg));
            }
        } catch (IOException ex) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
        return false;
    }
}
