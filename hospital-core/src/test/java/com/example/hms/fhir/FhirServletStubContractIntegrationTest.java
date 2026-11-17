package com.example.hms.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.example.hms.fhir.bulk.FhirBulkExportOperationProvider;
import com.example.hms.fhir.bulk.FhirBulkExportService;
import com.example.hms.fhir.smart.SmartConfigurationBuilder;
import com.example.hms.fhir.smart.SmartConfigurationInterceptor;
import com.example.hms.fhir.smart.SmartConfigurationProperties;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Actual production FHIR servlet/provider registration, discovery and tenant hooks.
 * Provider collaborators are mocks: no Spring database context or socket is started.
 * The servlet owns these URLs, so dispatch goes through Servlet.service rather than a fake MVC controller.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FhirServletStubContractIntegrationTest {

    private static final String[] TYPES = {"Patient", "Encounter", "Observation", "Condition",
        "MedicationRequest", "Immunization", "ServiceRequest", "DiagnosticReport",
        "Appointment", "Slot", "DocumentReference"};
    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final String ID = "00000000-0000-0000-0000-00000000cd05";
    private final Map<Class<?>, Object> collaborators = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final FhirContext context = FhirContext.forR4Cached();
    private FhirTenantBoundary boundary;
    private RestfulServer server;

    @BeforeAll
    void startServlet() throws Exception {
        boundary = mock(FhirTenantBoundary.class);
        collaborators.put(FhirTenantBoundary.class, boundary);
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(IResourceProvider.class));
        List<IResourceProvider> providers = new ArrayList<>();
        for (var bean : scanner.findCandidateComponents("com.example.hms.fhir.provider")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            Constructor<?>[] constructors = type.getConstructors();
            assertThat(constructors).as(type.getSimpleName()).hasSize(1);
            Object[] arguments = Arrays.stream(constructors[0].getParameterTypes())
                .map(parameter -> parameter == Clock.class
                    ? Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC)
                    : collaborators.computeIfAbsent(parameter, key -> mock(key, invocation ->
                    Page.class.isAssignableFrom(invocation.getMethod().getReturnType())
                        ? Page.empty() : Answers.RETURNS_DEFAULTS.answer(invocation)))).toArray();
            providers.add((IResourceProvider) constructors[0].newInstance(arguments));
        }
        assertThat(providers.stream().map(provider -> provider.getResourceType().getSimpleName()))
            .containsExactlyInAnyOrder(TYPES);
        var properties = new SmartConfigurationProperties();
        properties.setIssuer("https://cdx-fhir.example.invalid/api/fhir");
        properties.setAuthorizationEndpoint("https://cdx-issuer.example.invalid/authorize");
        properties.setTokenEndpoint("https://cdx-issuer.example.invalid/token");
        var builder = new SmartConfigurationBuilder(properties, "");
        server = new FhirConfig().fhirServletRegistration(context, providers,
            new SmartConfigurationInterceptor(builder, json), builder, new FhirWriteProperties(),
            new FhirOperationsProperties(), new FhirBulkExportOperationProvider(mock(FhirBulkExportService.class)),
            new FhirTenantBoundaryInterceptor(context, boundary), "/api/fhir").getServlet();
        var servletContext = new MockServletContext();
        servletContext.setContextPath("/api");
        server.init(new MockServletConfig(servletContext, "cdx-fhir"));
    }

    @BeforeEach
    void resetRequestState() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
        reset(boundary);
        clearInvocations(collaborators.values().toArray());
    }

    @AfterEach
    void clearRequestState() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    void stopServlet() {
        if (server != null) server.destroy();
    }

    private MockHttpServletResponse get(String path) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/fhir" + path);
        request.setContextPath("/api");
        request.setServletPath("/fhir");
        request.setPathInfo(path);
        request.setScheme("https");
        request.setServerName("cdx-fhir.example.invalid");
        request.setServerPort(443);
        request.addHeader("Accept", "application/fhir+json");
        var response = new MockHttpServletResponse();
        server.service(request, response);
        return response;
    }

    private void assertOutcome(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(json.readTree(response.getContentAsString()).path("resourceType").asText())
            .isEqualTo("OperationOutcome");
        verifyNoInteractions(collaborators.entrySet().stream()
            .filter(entry -> entry.getKey() != FhirTenantBoundary.class).map(Map.Entry::getValue).toArray());
    }

    @Test
    void capabilityStatementListsEveryReadSearchResourceAndSmartEndpoints() throws Exception {
        var response = get("/metadata");
        assertThat(response.getStatus()).isEqualTo(200);
        CapabilityStatement statement = context.newJsonParser()
            .parseResource(CapabilityStatement.class, response.getContentAsString());
        assertThat(statement.getFhirVersion().toCode()).isEqualTo("4.0.1");
        List<String> advertised = new ArrayList<>(Arrays.asList(TYPES));
        advertised.add("OperationDefinition"); // HAPI's framework-owned operation metadata.
        assertThat(statement.getRestFirstRep().getResource().stream().map(resource -> resource.getType()))
            .containsExactlyInAnyOrderElementsOf(advertised);
        statement.getRestFirstRep().getResource().stream()
            .filter(resource -> !"OperationDefinition".equals(resource.getType())).forEach(resource ->
            assertThat(resource.getInteraction().stream().map(interaction -> interaction.getCode()))
                .contains(CapabilityStatement.TypeRestfulInteraction.READ,
                    CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE));
        var security = statement.getRestFirstRep().getSecurity();
        assertThat(security.getServiceFirstRep().getCodingFirstRep().getCode()).isEqualTo("SMART-on-FHIR");
        var oauth = security.getExtensionByUrl("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris");
        assertThat(oauth.getExtensionByUrl("authorize").getValue().primitiveValue())
            .isEqualTo("https://cdx-issuer.example.invalid/authorize");
        assertThat(oauth.getExtensionByUrl("token").getValue().primitiveValue())
            .isEqualTo("https://cdx-issuer.example.invalid/token");
    }

    @Test
    void discoveryReturnsScopesAndPkceWithoutHospitalOrProviderCalls() throws Exception {
        var response = get("/.well-known/smart-configuration");
        assertThat(response.getStatus()).isEqualTo(200);
        var document = json.readTree(response.getContentAsString());
        assertThat(document.path("authorization_endpoint").asText())
            .isEqualTo("https://cdx-issuer.example.invalid/authorize");
        assertThat(document.path("token_endpoint").asText()).isEqualTo("https://cdx-issuer.example.invalid/token");
        assertThat(document.path("scopes_supported").toString()).contains("patient/*.read", "user/*.read");
        assertThat(document.path("code_challenge_methods_supported").toString()).contains("S256");
        verifyNoInteractions(collaborators.values().toArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Patient", "Encounter", "Observation", "Condition", "MedicationRequest", "Immunization",
        "ServiceRequest", "DiagnosticReport", "Appointment", "Slot", "DocumentReference"})
    void readAndSearchWithoutHospitalAreRefusedBeforeAnyProvider(String type) throws Exception {
        assertOutcome(get("/" + type + "/" + ID), 403);
        assertOutcome(get("/" + type), 403);
        verifyNoInteractions(boundary);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Patient", "Encounter", "Observation", "Condition", "MedicationRequest", "Immunization",
        "ServiceRequest", "DiagnosticReport", "Appointment", "Slot", "DocumentReference"})
    void hospitalWithoutRequiredRoleCannotReadOrSearch(String type) throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(HOSPITAL)
            .permittedHospitalIds(Set.of(HOSPITAL)).build());
        assertOutcome(get("/" + type + "/" + ID), 403);
        assertOutcome(get("/" + type), 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Patient", "Encounter", "Observation", "Condition", "MedicationRequest", "Immunization",
        "ServiceRequest", "DiagnosticReport", "Appointment", "Slot", "DocumentReference"})
    void invisibleNamedResourceIsNotFoundBeforeProviderRead(String type) throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(HOSPITAL)
            .permittedHospitalIds(Set.of(HOSPITAL)).build());
        when(boundary.holdsRoleAt(any(), any(), any(), any())).thenReturn(true);
        assertOutcome(get("/" + type + "/" + ID), 404);
        verify(boundary).isVisible(type, ID, HOSPITAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Patient", "Encounter", "Observation", "Condition", "MedicationRequest", "Immunization",
        "ServiceRequest", "DiagnosticReport", "Appointment", "Slot", "DocumentReference"})
    void permittedSearchSerializesAnEmptyBundleForEachRealProvider(String type) throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(HOSPITAL)
            .permittedHospitalIds(Set.of(HOSPITAL)).build());
        when(boundary.holdsRoleAt(any(), any(), any(), any())).thenReturn(true);
        var response = get("/" + type);
        assertThat(response.getStatus()).as(type + ": " + response.getContentAsString()).isEqualTo(200);
        var bundle = json.readTree(response.getContentAsString());
        assertThat(bundle.path("resourceType").asText()).isEqualTo("Bundle");
        assertThat(bundle.path("total").asInt()).isZero();
        assertThat(bundle.path("entry").size()).isZero();
    }
}
