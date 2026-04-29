package com.example.hms.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;
import com.example.hms.fhir.smart.HmsCapabilityStatementProvider;
import com.example.hms.fhir.smart.SmartConfigurationBuilder;
import com.example.hms.fhir.smart.SmartConfigurationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Mounts a HAPI FHIR R4 plain-server at <code>/fhir/*</code> (becomes
 * <code>/api/fhir/*</code> via the application context-path).
 *
 * <p>Resource providers are auto-discovered via Spring DI and registered
 * with the {@link RestfulServer}. The default {@code CapabilityStatement}
 * advertises every registered provider, so adding a new {@link IResourceProvider}
 * bean is sufficient to surface it on the wire.
 *
 * <p>Two HAPI interceptors are layered on top:
 * <ul>
 *   <li>{@link SmartConfigurationInterceptor} — serves the spec-conformant
 *       {@code .well-known/smart-configuration} endpoint inside the FHIR
 *       servlet space.</li>
 *   <li>{@link HmsCapabilityStatementProvider} replaces the default
 *       capability provider with one that advertises the SMART OAuth
 *       security extension.</li>
 * </ul>
 */
@Configuration
public class FhirConfig {

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();
    }

    @Bean
    public ServletRegistrationBean<RestfulServer> fhirServletRegistration(
        FhirContext fhirContext,
        List<IResourceProvider> resourceProviders,
        SmartConfigurationInterceptor smartConfigurationInterceptor,
        SmartConfigurationBuilder smartConfigurationBuilder,
        @Value("${app.fhir.serverBaseUrl:/api/fhir}") String serverBaseUrl
    ) {
        RestfulServer server = new RestfulServer(fhirContext);
        server.setServerName("HMS FHIR R4 Server");
        server.setServerVersion("0.1.0");
        server.setImplementationDescription(
            "Hospital Management System FHIR R4 façade — read-only "
                + "Patient/Encounter/Observation/Condition/MedicationRequest/Immunization."
        );
        server.setServerAddressStrategy(new ApacheProxyAddressStrategy(serverBaseUrl));
        server.setResourceProviders(resourceProviders);
        server.setDefaultPrettyPrint(true);
        server.registerInterceptor(new ResponseHighlighterInterceptor());
        server.registerInterceptor(smartConfigurationInterceptor);
        server.setServerConformanceProvider(new HmsCapabilityStatementProvider(server, smartConfigurationBuilder));

        ServletRegistrationBean<RestfulServer> reg = new ServletRegistrationBean<>(server, "/fhir/*");
        reg.setName("fhirServlet");
        reg.setLoadOnStartup(1);
        return reg;
    }
}
