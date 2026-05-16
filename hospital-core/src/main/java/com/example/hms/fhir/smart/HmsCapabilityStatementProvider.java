package com.example.hms.fhir.smart;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import com.example.hms.fhir.FhirOperationsProperties;
import com.example.hms.fhir.FhirWriteProperties;
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
 *
 * <p>When the FHIR write API (roadmap row 20) is enabled via
 * {@link FhirWriteProperties#isEnabled()}, the Patient resource entry is
 * augmented with {@code conditionalCreate=true} so consumers (Cerner,
 * Epic, SMART app clients) discover the supported write semantics.
 * The {@code update} interaction is auto-surfaced by HAPI from the
 * provider's {@code @Update} method; the explicit augmentation here
 * only adds the conditional-create flag which HAPI does not infer.
 */
public class HmsCapabilityStatementProvider extends ServerCapabilityStatementProvider {

    private static final String OAUTH_URIS_EXTENSION =
        "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris";
    private static final String SMART_AUTHORIZE_URL_EXTENSION = "authorize";
    private static final String SMART_TOKEN_URL_EXTENSION = "token";

    private final SmartConfigurationBuilder smartBuilder;
    private final FhirWriteProperties writeProperties;
    private final FhirOperationsProperties operationsProperties;

    public HmsCapabilityStatementProvider(
        RestfulServer server,
        SmartConfigurationBuilder smartBuilder,
        FhirWriteProperties writeProperties,
        FhirOperationsProperties operationsProperties
    ) {
        super(server);
        this.smartBuilder = smartBuilder;
        this.writeProperties = writeProperties;
        this.operationsProperties = operationsProperties;
    }

    @Override
    public org.hl7.fhir.instance.model.api.IBaseConformance getServerConformance(
        HttpServletRequest theRequest, RequestDetails theRequestDetails
    ) {
        Object base = super.getServerConformance(theRequest, theRequestDetails);
        if (base instanceof CapabilityStatement cs && theRequest != null) {
            applySmartSecurity(cs, theRequest);
            applyPatientWriteCapabilities(cs);
            applyOperationVisibility(cs);
        }
        return (org.hl7.fhir.instance.model.api.IBaseConformance) base;
    }

    /**
     * HAPI auto-emits {@code operation} entries for every
     * {@code @Operation} discovered on a registered provider. The
     * default emission is unconditional — so {@code $export} and
     * {@code $everything} would appear in {@code /metadata} even when
     * their flags are off and every invocation returns 405. This
     * method strips those entries when the corresponding flag is off
     * so the published contract matches runtime behaviour.
     *
     * <p>HAPI places operations at two levels:
     * <ul>
     *   <li>{@code rest[].operation} — system-level operations
     *       (no {@code type=} on the {@code @Operation} annotation).</li>
     *   <li>{@code rest[].resource[].operation} — resource-level
     *       operations ({@code type=Patient.class} on a plain
     *       provider's {@code $export}, or an {@code @Operation}
     *       directly on a resource provider for {@code $everything}).</li>
     * </ul>
     * Both lists must be visited or the resource-level entries leak
     * through and contradict the flag-off contract.
     */
    private void applyOperationVisibility(CapabilityStatement cs) {
        if (cs.getRest().isEmpty()) return;
        boolean bulkOn = operationsProperties != null
            && operationsProperties.getBulkExport().isEnabled();
        boolean everythingOn = operationsProperties != null
            && operationsProperties.getEverything().isEnabled();
        cs.getRestFirstRep().getOperation().removeIf(op -> shouldStrip(op.getName(), bulkOn, everythingOn));
        cs.getRestFirstRep().getResource().forEach(r ->
            r.getOperation().removeIf(op -> shouldStrip(op.getName(), bulkOn, everythingOn))
        );
    }

    private static boolean shouldStrip(String operationName, boolean bulkOn, boolean everythingOn) {
        if (operationName == null) return false;
        if (!bulkOn && "export".equals(operationName)) return true;
        if (!everythingOn && "everything".equals(operationName)) return true;
        return false;
    }

    private void applyPatientWriteCapabilities(CapabilityStatement cs) {
        if (cs.getRest().isEmpty()) return;
        cs.getRestFirstRep().getResource().stream()
            .filter(r -> "Patient".equals(r.getType()))
            .findFirst()
            .ifPresent(r -> {
                boolean enabled = writeProperties != null && writeProperties.isEnabled();
                if (enabled) {
                    r.setConditionalCreate(true);
                    return;
                }
                // Flag off: actively strip the write affordances HAPI auto-
                // advertises from the @Create / @Update annotations + the
                // @ConditionalUrlParam, so the CapabilityStatement matches the
                // runtime behaviour (PatientFhirWriteService throws
                // MethodNotAllowedException). Without this, HAPI emits
                // interaction=create / interaction=update / conditionalCreate=true
                // even when every write call returns 405 — that's a misleading
                // contract for consumers that probe metadata before invoking.
                r.setConditionalCreate(false);
                r.getInteraction().removeIf(i -> i.getCode() != null
                    && (i.getCode() == CapabilityStatement.TypeRestfulInteraction.CREATE
                     || i.getCode() == CapabilityStatement.TypeRestfulInteraction.UPDATE));
            });
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
