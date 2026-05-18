package com.example.hms.security.tenant.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.TenantIsolationMode;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for the row-33 provisioning-wiring service. Pins the
 * SAFE_IDENTIFIER rejection (security-critical — same allowlist as
 * the shell script), the cross-tenant guard (refuses to re-provision
 * a hospital already in SCHEMA mode), and the three DDL statements
 * the service issues (CREATE SCHEMA + GRANT USAGE + ALTER DEFAULT
 * PRIVILEGES).
 */
@ExtendWith(MockitoExtension.class)
class TenantProvisioningServiceTest {

    @Mock private EntityManager entityManager;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private UserRepository userRepository;
    @Mock private Query query;

    private TenantProvisioningService service;

    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SchemaTenancyProperties> propsProvider = mock(ObjectProvider.class);
        when(propsProvider.getIfAvailable()).thenReturn(null);
        service = new TenantProvisioningService(
            entityManager, hospitalRepository, auditEventLogService, userRepository,
            propsProvider, /* provisioningFlagEnabled */ true, "hms_app");
    }

    @Test
    @DisplayName("isEnabled reflects the constructor flag")
    void isEnabledFlag() {
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("rejects null hospitalId with IllegalArgumentException")
    void rejectsNullHospitalId() {
        assertThatThrownBy(() -> service.provision(null, "tenant_xxx"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hospitalId");
    }

    @Test
    @DisplayName("rejects schemaName failing SAFE_IDENTIFIER (security-critical)")
    void rejectsBadSchemaName() {
        // Capital letters → fails the [a-z][a-z0-9_]{0,62} allowlist
        assertThatThrownBy(() -> service.provision(hospitalId, "Tenant_BAD"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SAFE_IDENTIFIER");
        // SQL-injection attempt with semicolons / quotes
        assertThatThrownBy(() -> service.provision(hospitalId, "x; DROP TABLE users;--"))
            .isInstanceOf(IllegalArgumentException.class);
        // Too long (>63 chars) → also fails the allowlist
        assertThatThrownBy(() -> service.provision(hospitalId, "a".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
        verify(entityManager, never()).createNativeQuery(any(String.class));
    }

    @Test
    @DisplayName("rejects unknown hospital UUID with IllegalArgumentException")
    void rejectsUnknownHospital() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.provision(hospitalId, "tenant_xxx"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
        verify(entityManager, never()).createNativeQuery(any(String.class));
    }

    @Test
    @DisplayName("rejects hospital already in SCHEMA mode with IllegalStateException")
    void rejectsAlreadySchemaMode() {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        h.setCode("BFQ_MIL_001");
        h.setIsolationMode(TenantIsolationMode.SCHEMA);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(h));

        assertThatThrownBy(() -> service.provision(hospitalId, "tenant_xxx"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already in SCHEMA");
        verify(entityManager, never()).createNativeQuery(any(String.class));
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    @DisplayName("happy path issues CREATE SCHEMA + GRANT USAGE + ALTER DEFAULT PRIVILEGES + audit")
    void happyPath() {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        h.setCode("BFQ_MIL_001");
        h.setIsolationMode(TenantIsolationMode.ROW_LEVEL);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(h));
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(query);
        when(query.executeUpdate()).thenReturn(0);

        String resolved = service.provision(hospitalId, "tenant_bfq_mil_001");

        assertThat(resolved).isEqualTo("tenant_bfq_mil_001");
        // Verify the three DDL statements the script issues are
        // emitted via createNativeQuery. Capture the SQL so the
        // assertion proves the allowlisted schema name is what
        // landed in the statement (the same rationale the shell
        // script's PR #356 SAFE_REGEX-everywhere lesson encodes).
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, org.mockito.Mockito.times(3)).createNativeQuery(sql.capture());
        assertThat(sql.getAllValues())
            .anyMatch(s -> s.contains("CREATE SCHEMA IF NOT EXISTS tenant_bfq_mil_001"))
            .anyMatch(s -> s.contains("GRANT USAGE ON SCHEMA tenant_bfq_mil_001 TO hms_app"))
            .anyMatch(s -> s.contains("ALTER DEFAULT PRIVILEGES IN SCHEMA tenant_bfq_mil_001"));

        verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    @DisplayName("audit emission failure does not roll back the provisioning")
    void auditFailureTolerant() {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        h.setCode("X");
        h.setIsolationMode(TenantIsolationMode.ROW_LEVEL);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(h));
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(query);
        when(query.executeUpdate()).thenReturn(0);
        org.mockito.Mockito.doThrow(new RuntimeException("splunk down"))
            .when(auditEventLogService).logEvent(any());

        // Doesn't throw — audit emission is fail-tolerant per the
        // phi-encryption-audit skill rule.
        assertThat(service.provision(hospitalId, "tenant_ok")).isEqualTo("tenant_ok");
    }

    @Test
    @DisplayName("rejects a configured app-role that fails SAFE_IDENTIFIER")
    void rejectsBadConfiguredAppRole() {
        // The app-role validation runs BEFORE the hospital lookup, so
        // no hospital stub is needed — the throw short-circuits the
        // whole flow at the constructor-injected role check.
        @SuppressWarnings("unchecked")
        ObjectProvider<SchemaTenancyProperties> propsProvider = mock(ObjectProvider.class);
        when(propsProvider.getIfAvailable()).thenReturn(null);
        TenantProvisioningService badRoleService = new TenantProvisioningService(
            entityManager, hospitalRepository, auditEventLogService, userRepository,
            propsProvider, true, "BAD-ROLE");

        assertThatThrownBy(() -> badRoleService.provision(hospitalId, "tenant_ok"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("app-role");
    }
}
