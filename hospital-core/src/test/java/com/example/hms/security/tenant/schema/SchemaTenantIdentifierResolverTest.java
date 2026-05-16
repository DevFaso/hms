package com.example.hms.security.tenant.schema;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SchemaTenantIdentifierResolverTest {

    private final TenantSchemaLookup lookup = mock(TenantSchemaLookup.class);
    private final SchemaTenantIdentifierResolver resolver = new SchemaTenantIdentifierResolver(lookup);

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @Test
    void returnsDefaultWhenNoActiveHospital() {
        // No HospitalContext set — resolver must fall back to DEFAULT
        // and never bother the lookup (jobs / Liquibase / boot path).
        assertThat(resolver.resolveCurrentTenantIdentifier())
            .isEqualTo(SchemaTenantIdentifierResolver.DEFAULT_TENANT);
        verifyNoInteractions(lookup);
    }

    @Test
    void returnsDefaultForRowLevelHospital() {
        UUID hospitalId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId).build());
        when(lookup.schemaFor(hospitalId)).thenReturn(Optional.empty());

        assertThat(resolver.resolveCurrentTenantIdentifier())
            .isEqualTo(SchemaTenantIdentifierResolver.DEFAULT_TENANT);
    }

    @Test
    void returnsSchemaNameForSchemaIsolatedHospital() {
        UUID hospitalId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId).build());
        when(lookup.schemaFor(hospitalId)).thenReturn(Optional.of("tenant_alpha"));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo("tenant_alpha");
    }

    @Test
    void validatesExistingSessionsToPreventCrossTenantLeak() {
        // If we returned false, Hibernate would happily reuse a Session
        // across tenant transitions and silently route a new request's
        // queries through the previous tenant's connection provider.
        assertThat(resolver.validateExistingCurrentSessions()).isTrue();
    }
}
