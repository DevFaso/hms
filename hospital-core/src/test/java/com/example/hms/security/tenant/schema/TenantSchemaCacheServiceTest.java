package com.example.hms.security.tenant.schema;

import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantSchemaCacheService}. Pins the
 * foundation-pass contract: invalidate-one + audit emission + the
 * flag-off / no-bean degradation path.
 */
class TenantSchemaCacheServiceTest {

    private SchemaTenancyProperties properties;
    private TenantSchemaLookup lookup;
    private AuditEventLogService auditService;
    private TenantSchemaCacheService service;

    @BeforeEach
    void setUp() {
        properties = new SchemaTenancyProperties();
        lookup = mock(TenantSchemaLookup.class);
        auditService = mock(AuditEventLogService.class);
        service = new TenantSchemaCacheService(
            providerOf(properties),
            providerOf(lookup),
            auditService,
            mock(UserRepository.class)
        );
    }

    @Test
    @DisplayName("isEnabled reflects both the property and the lookup bean presence")
    void isEnabledReflectsPropertyAndBean() {
        assertThat(service.isEnabled()).isFalse();
        properties.setEnabled(true);
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("isEnabled stays false when the lookup bean is absent (flag-off topology)")
    void isEnabledFalseWithoutLookupBean() {
        properties.setEnabled(true);
        TenantSchemaCacheService noLookup = new TenantSchemaCacheService(
            providerOf(properties),
            providerOf(null),
            auditService,
            mock(UserRepository.class)
        );
        assertThat(noLookup.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("invalidate is a no-op + skips audit when flag off")
    void noOpWhenFlagOff() {
        service.invalidate(UUID.randomUUID());
        verifyNoInteractions(lookup);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("invalidate skips work + audit when hospitalId is null")
    void noOpWhenHospitalIdNull() {
        properties.setEnabled(true);
        service.invalidate(null);
        verify(lookup, never()).invalidate(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("invalidate drops the cache entry + emits a TENANT_SCHEMA_CACHE_INVALIDATED audit row when flag on")
    void invalidateDropsAndAudits() {
        properties.setEnabled(true);
        UUID hospitalId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        service.invalidate(hospitalId);
        verify(lookup).invalidate(hospitalId);
        ArgumentCaptor<AuditEventRequestDTO> captor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(captor.capture());
        AuditEventRequestDTO sent = captor.getValue();
        assertThat(sent.getEventType().name()).isEqualTo("TENANT_SCHEMA_CACHE_INVALIDATED");
        assertThat(sent.getEntityType()).isEqualTo("HOSPITAL");
        assertThat(sent.getResourceId()).isEqualTo(hospitalId.toString());
        assertThat(sent.getEventDescription()).contains(hospitalId.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
