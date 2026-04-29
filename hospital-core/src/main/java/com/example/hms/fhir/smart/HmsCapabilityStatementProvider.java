package com.example.hms.fhir.smart;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import com.example.hms.fhir.smart.SmartConfigurationController.SmartConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.UriType;

/**
 * Wraps HAPI's default {@link ServerCapabilityStatementProvider} and adds the
 * SMART-on-FHIR OAuth security extension to the {@code rest[].security} block.
 *
 * <p>This is the conformance-correct way to advertise SMART support: the
 * {@code CapabilityStatement} carries the authorize/token URLs, and the
 * {@code .well-known/smart-configuration} endpoint carries the same data
 * in a more digestible JSON.
 */
public class HmsCapabilityStatementProvider extends ServerCapabilityStatementProvider {

    private static final String OAUTH_URIS_EXTENSION =
        "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris";
    private static final String SMART_AUTHORIZE_URL_EXTENSION = "authorize";
    private static final String SMART_TOKEN_URL_EXTENSION = "token";

    private final SmartConfigurationBuilder smartBuilder;

    public HmsCapabilityStatementProvider(RestfulServer server, SmartConfigurationBuilder smartBuilder) {
        super(server);
        this.smartBuilder = smartBuilder;
    }

    @Override
    public org.hl7.fhir.instance.model.api.IBaseConformance getServerConformance(
        HttpServletRequest theRequest, RequestDetails theRequestDetails
    ) {
        Object base = super.getServerConformance(theRequest, theRequestDetails);
        if (base instanceof CapabilityStatement cs && theRequest != null) {
            applySmartSecurity(cs, theRequest);
        }
        return (org.hl7.fhir.instance.model.api.IBaseConformance) base;
    }

    private void applySmartSecurity(CapabilityStatement cs, HttpServletRequest request) {
        if (cs.getRest().isEmpty()) return;
        SmartConfiguration smart = smartBuilder.build(request);
        CapabilityStatement.CapabilityStatementRestSecurityComponent security =
            cs.getRestFirstRep().getSecurity();

        // Mark this endpoint as CORS-enabled and bind it to the SMART service spec.
        security.setCors(true);
        security.addService(new CodeableConcept().addCoding(new Coding()
            .setSystem("http://terminology.hl7.org/CodeSystem/restful-security-service")
            .setCode("SMART-on-FHIR")
            .setDisplay("SMART-on-FHIR")));

        // OAuth URIs extension.
        Extension oauthUris = new Extension(OAUTH_URIS_EXTENSION);
        if (smart.authorization_endpoint() != null) {
            oauthUris.addExtension(new Extension(SMART_AUTHORIZE_URL_EXTENSION,
                new UriType(smart.authorization_endpoint())));
        }
        if (smart.token_endpoint() != null) {
            oauthUris.addExtension(new Extension(SMART_TOKEN_URL_EXTENSION,
                new UriType(smart.token_endpoint())));
        }
        if (oauthUris.hasExtension()) {
            security.addExtension(oauthUris);
        }
    }
}
