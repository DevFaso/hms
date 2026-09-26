package com.example.hms.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Each step of the boundary alone, with the resolver mocked: the IT proves the
 * whole servlet; this pins which step does what, so removing one step fails a
 * test here even when another step would have caught the same request.
 */
class FhirTenantBoundaryInterceptorTest {

    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();
    private static final String OWN = UUID.randomUUID().toString();
    private static final String FOREIGN = UUID.randomUUID().toString();

    private final FhirContext fhirContext = FhirContext.forR4Cached();
    private final FhirTenantBoundary boundary = mock(FhirTenantBoundary.class);
    private final FhirTenantBoundaryInterceptor interceptor = new FhirTenantBoundaryInterceptor(fhirContext, boundary);

    @BeforeEach
    void holdsTheRole() {
        // Every test below acts as someone who holds the role at the bound
        // hospital, except the ones that say otherwise.
        when(boundary.holdsRoleAt(any(), any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    private static void actAt(UUID active, Set<UUID> permitted, boolean superAdmin, boolean headerOverridden) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(active)
            .permittedHospitalIds(permitted)
            .superAdmin(superAdmin)
            .headerOverridden(headerOverridden)
            .build());
    }

    private static SystemRequestDetails request(RestOperationTypeEnum operation, String resourceName, String id) {
        SystemRequestDetails request = new SystemRequestDetails();
        request.setRestOperationType(operation);
        request.setResourceName(resourceName);
        if (id != null) {
            request.setId(new IdType(resourceName, id));
        }
        return request;
    }

    // ------------------------------------------------------------ step 1: bind

    @Test
    @DisplayName("metadata passes without any scope")
    void metadataNeedsNoScope() {
        SystemRequestDetails request = request(RestOperationTypeEnum.METADATA, null, null);
        interceptor.bindAndGate(request, RestOperationTypeEnum.METADATA);
        ResponseDetails response = new ResponseDetails(200, new CapabilityStatement());
        interceptor.filterOutgoing(request, response);
        verifyNoInteractions(boundary);
    }

    @Test
    @DisplayName("no scope is a 403 before any provider runs")
    void noScopeIsForbidden() {
        SystemRequestDetails request = request(RestOperationTypeEnum.READ, "Encounter", OWN);
        assertThatThrownBy(() -> interceptor.bindAndGate(request, RestOperationTypeEnum.READ))
            .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(boundary);
    }

    @Test
    @DisplayName("the bound hospital comes from the principal: a header choice outside it binds nothing")
    void boundHospitalIsThePrincipals() {
        // Held hospital, chosen by header or not.
        assertThat(FhirTenantBoundary.boundHospital(ctx(HOSPITAL_A, Set.of(HOSPITAL_A), false, false)))
            .isEqualTo(HOSPITAL_A);
        assertThat(FhirTenantBoundary.boundHospital(ctx(HOSPITAL_B, Set.of(HOSPITAL_A, HOSPITAL_B), false, true)))
            .isEqualTo(HOSPITAL_B);
        // No permitted hospital at all: HospitalContextRequestOverrides took the header anyway.
        assertThat(FhirTenantBoundary.boundHospital(ctx(HOSPITAL_B, Set.of(), false, true))).isNull();
        // An active hospital the principal does not hold.
        assertThat(FhirTenantBoundary.boundHospital(ctx(HOSPITAL_B, Set.of(HOSPITAL_A), false, false))).isNull();
        // A super-admin is global until pinned, and bound once pinned.
        assertThat(FhirTenantBoundary.boundHospital(ctx(HOSPITAL_A, Set.of(HOSPITAL_A), true, false))).isNull();
        assertThat(FhirTenantBoundary.boundHospital(ctx(HOSPITAL_B, Set.of(), true, true))).isEqualTo(HOSPITAL_B);
        assertThat(FhirTenantBoundary.boundHospital(null)).isNull();
    }

    private static HospitalContext ctx(UUID active, Set<UUID> permitted, boolean superAdmin, boolean header) {
        return HospitalContext.builder().activeHospitalId(active).permittedHospitalIds(permitted)
            .superAdmin(superAdmin).headerOverridden(header).build();
    }

    // -------------------------------------------------- step 2: the named id

    @Test
    @DisplayName("a named id that is not visible is refused before the provider, with the provider's own not-found")
    void namedIdIsGatedBeforeTheProvider() {
        actAt(HOSPITAL_A, Set.of(HOSPITAL_A), false, false);
        when(boundary.isVisible("Encounter", FOREIGN, HOSPITAL_A)).thenReturn(false);
        SystemRequestDetails request = request(RestOperationTypeEnum.READ, "Encounter", FOREIGN);

        assertThatThrownBy(() -> interceptor.bindAndGate(request, RestOperationTypeEnum.READ))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage(new ResourceNotFoundException(new IdType("Encounter", FOREIGN)).getMessage());
    }

    @Test
    @DisplayName("a visible named id passes, and the bound hospital travels with the request")
    void visibleNamedIdPasses() {
        actAt(HOSPITAL_A, Set.of(HOSPITAL_A), false, false);
        when(boundary.isVisible("Encounter", OWN, HOSPITAL_A)).thenReturn(true);
        SystemRequestDetails request = request(RestOperationTypeEnum.UPDATE, "Encounter", OWN);

        interceptor.bindAndGate(request, RestOperationTypeEnum.UPDATE);

        assertThat(request.getUserData()).containsEntry(FhirTenantBoundaryInterceptor.BOUND_HOSPITAL, HOSPITAL_A);
    }

    // -------------------------------------------------------- step 3: output

    @Test
    @DisplayName("a search loses its paging parameters, so the bundle it filters is the whole result")
    void searchLosesPaging() {
        actAt(HOSPITAL_A, Set.of(HOSPITAL_A), false, false);
        SystemRequestDetails request = request(RestOperationTypeEnum.SEARCH_TYPE, "Encounter", null);
        Map<String, String[]> params = new HashMap<>();
        params.put(Constants.PARAM_COUNT, new String[] {"1"});
        params.put(Constants.PARAM_OFFSET, new String[] {"1"});
        params.put("patient", new String[] {OWN});
        request.setParameters(params);

        interceptor.bindAndGate(request, RestOperationTypeEnum.SEARCH_TYPE);

        assertThat(request.getParameters()).containsOnlyKeys("patient");
    }

    @Test
    @DisplayName("a search bundle keeps only the visible entries, and its total follows")
    void bundleIsFiltered() {
        SystemRequestDetails request = boundSearch();
        when(boundary.visibleIdParts(eq("Encounter"), any(), eq(HOSPITAL_A))).thenReturn(Set.of(OWN));
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.addEntry().setResource(new Encounter().setId(OWN));
        bundle.addEntry().setResource(new Encounter().setId(FOREIGN));
        bundle.addEntry().setResource(new Encounter());          // no id: cannot be vouched for
        bundle.addEntry().setResource(new OperationOutcome().setId("oo"));
        bundle.setTotal(3);

        interceptor.filterOutgoing(request, new ResponseDetails(200, bundle));

        assertThat(bundle.getEntry()).extracting(e -> e.getResource().getIdElement().getIdPart())
            .containsExactly(OWN, "oo");
        assertThat(bundle.getTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("a type the boundary was never taught is withheld entirely")
    void unknownTypeIsWithheld() {
        SystemRequestDetails request = boundSearch();
        when(boundary.visibleIdParts(anyString(), any(), eq(HOSPITAL_A))).thenReturn(Set.of());
        Bundle bundle = new Bundle();
        bundle.addEntry().setResource(new org.hl7.fhir.r4.model.Medication().setId(OWN));

        interceptor.filterOutgoing(request, new ResponseDetails(200, bundle));

        assertThat(bundle.getEntry()).isEmpty();
    }

    @Test
    @DisplayName("a single resource that is not visible answers the request's own not-found")
    void singleResourceIsChecked() {
        actAt(HOSPITAL_A, Set.of(HOSPITAL_A), false, false);
        when(boundary.isVisible("Encounter", OWN, HOSPITAL_A)).thenReturn(true);
        SystemRequestDetails request = request(RestOperationTypeEnum.READ, "Encounter", OWN);
        interceptor.bindAndGate(request, RestOperationTypeEnum.READ);
        // The provider handed back somebody else's row for the id it was asked.
        when(boundary.isVisible("Encounter", FOREIGN, HOSPITAL_A)).thenReturn(false);

        assertThatThrownBy(() -> interceptor.filterOutgoing(request,
            new ResponseDetails(200, new Encounter().setId(FOREIGN))))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage(new ResourceNotFoundException(new IdType("Encounter", OWN)).getMessage());
    }

    @Test
    @DisplayName("a response without a bound hospital lets nothing through")
    void unboundResponseIsEmpty() {
        SystemRequestDetails request = request(RestOperationTypeEnum.SEARCH_TYPE, "Encounter", null);
        Bundle bundle = new Bundle();
        bundle.addEntry().setResource(new Encounter().setId(OWN));

        interceptor.filterOutgoing(request, new ResponseDetails(200, bundle));

        assertThat(bundle.getEntry()).isEmpty();
        assertThatThrownBy(() -> interceptor.filterOutgoing(request,
            new ResponseDetails(200, new Patient().setId(OWN))))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("$everything's bundle is its own: step 3 leaves it to the E8 policy")
    void everythingIsSelfScoped() {
        SystemRequestDetails request = boundSearch();
        request.setRestOperationType(RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE);
        request.setOperation("$everything");
        Bundle bundle = new Bundle();
        bundle.addEntry().setResource(new Encounter().setId(FOREIGN));

        interceptor.filterOutgoing(request, new ResponseDetails(200, bundle));

        assertThat(bundle.getEntry()).hasSize(1);
        verify(boundary, never()).visibleIdParts(anyString(), any(), any());
    }

    @Test
    @DisplayName("an OperationOutcome passes untouched")
    void envelopePasses() {
        SystemRequestDetails request = boundSearch();
        interceptor.filterOutgoing(request, new ResponseDetails(200, new OperationOutcome()));
        verify(boundary, never()).isVisible(anyString(), anyString(), any());
        verify(boundary, never()).visibleIdParts(anyString(), any(), any());
    }

    // ------------------------------------------ step 1b: the role, here

    @Test
    @DisplayName("a caller without the role AT the bound hospital is refused before the named id is looked at")
    void roleNotHeldHere() {
        actAt(HOSPITAL_B, Set.of(HOSPITAL_A, HOSPITAL_B), false, true);
        when(boundary.holdsRoleAt(any(), any(), eq(HOSPITAL_B), any())).thenReturn(false);
        SystemRequestDetails request = request(RestOperationTypeEnum.READ, "Encounter", FOREIGN);

        assertThatThrownBy(() -> interceptor.bindAndGate(request, RestOperationTypeEnum.READ))
            .isInstanceOf(ForbiddenOperationException.class);
        verify(boundary, never()).isVisible(anyString(), anyString(), any());
        assertThat(request.getUserData()).doesNotContainKey(FhirTenantBoundaryInterceptor.BOUND_HOSPITAL);
    }

    @Test
    @DisplayName("reads need a reader's role, $export the admin's, and everything else a writer's")
    void requiredRolesFollowTheOperation() {
        for (RestOperationTypeEnum read : new RestOperationTypeEnum[] {RestOperationTypeEnum.READ,
            RestOperationTypeEnum.VREAD, RestOperationTypeEnum.SEARCH_TYPE, RestOperationTypeEnum.SEARCH_SYSTEM,
            RestOperationTypeEnum.HISTORY_INSTANCE, RestOperationTypeEnum.GET_PAGE}) {
            assertThat(FhirTenantBoundaryInterceptor.requiredRoleCodes(request(read, "Encounter", null), read))
                .as(read.name()).isSameAs(FhirTenantBoundary.READ_ROLE_CODES);
        }
        for (RestOperationTypeEnum write : new RestOperationTypeEnum[] {RestOperationTypeEnum.UPDATE,
            RestOperationTypeEnum.CREATE, RestOperationTypeEnum.PATCH, RestOperationTypeEnum.DELETE,
            RestOperationTypeEnum.TRANSACTION}) {
            assertThat(FhirTenantBoundaryInterceptor.requiredRoleCodes(request(write, "Encounter", null), write))
                .as(write.name()).isSameAs(FhirTenantBoundary.WRITE_ROLE_CODES);
        }
        SystemRequestDetails everything = request(RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE, "Patient", OWN);
        everything.setOperation("$everything");
        assertThat(FhirTenantBoundaryInterceptor.requiredRoleCodes(everything,
            RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE)).isSameAs(FhirTenantBoundary.READ_ROLE_CODES);
        SystemRequestDetails export = request(RestOperationTypeEnum.EXTENDED_OPERATION_SERVER, null, null);
        export.setOperation("$export");
        assertThat(FhirTenantBoundaryInterceptor.requiredRoleCodes(export,
            RestOperationTypeEnum.EXTENDED_OPERATION_SERVER)).isSameAs(FhirTenantBoundary.EXPORT_ROLE_CODES);
        SystemRequestDetails unknown = request(RestOperationTypeEnum.EXTENDED_OPERATION_TYPE, "Patient", null);
        unknown.setOperation("$match");
        assertThat(FhirTenantBoundaryInterceptor.requiredRoleCodes(unknown,
            RestOperationTypeEnum.EXTENDED_OPERATION_TYPE)).isSameAs(FhirTenantBoundary.WRITE_ROLE_CODES);
    }

    private SystemRequestDetails boundSearch() {
        actAt(HOSPITAL_A, Set.of(HOSPITAL_A), false, false);
        SystemRequestDetails request = request(RestOperationTypeEnum.SEARCH_TYPE, "Encounter", null);
        request.setParameters(new HashMap<>(Map.of("patient", new String[] {OWN})));
        interceptor.bindAndGate(request, RestOperationTypeEnum.SEARCH_TYPE);
        return request;
    }

}
