package com.example.hms.fhir.smart;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Holder for the SMART-on-FHIR App Launch 1.0 configuration JSON shape.
 *
 * <p>The actual {@code GET /fhir/.well-known/smart-configuration} response
 * is served by {@link SmartConfigurationInterceptor} because the FHIR
 * servlet at {@code /fhir/*} claims that URL space — Spring MVC controllers
 * cannot handle it directly.
 */
public final class SmartConfigurationController {

    private SmartConfigurationController() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SmartConfiguration(
        String issuer,
        String authorization_endpoint,
        String token_endpoint,
        String introspection_endpoint,
        String revocation_endpoint,
        List<String> scopes_supported,
        List<String> response_types_supported,
        List<String> code_challenge_methods_supported,
        List<String> capabilities
    ) {}
}
