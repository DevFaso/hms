package com.example.hms.security.tenant.schema;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Cache-invalidation admin surface for the schema-per-tenant cutover
 * (roadmap row 33 follow-on, v2.0 / Multi-tenancy).
 *
 * <p>The cutover runbook
 * ({@code docs/runbooks/schema-per-tenant-migration.md}, Step 4) flips
 * {@code hospital.hospitals.isolation_mode} from {@code ROW_LEVEL} to
 * {@code SCHEMA} inside a transaction. After that commit the
 * application's {@link TenantSchemaLookup} cache (5-min TTL) still
 * serves the old value for up to 5 minutes — the operator either
 * waits for the TTL or, with this service, invokes
 * {@code TenantSchemaLookup#invalidate(UUID)} so the next request to
 * that hospital resolves to the new schema immediately.
 *
 * <p>Gated by {@code app.tenancy.schema-isolation.enabled} (the same
 * flag that gates the whole schema-tenancy path). When the flag is
 * off the lookup bean does not exist; this service degrades to a
 * "not enabled" state and the controller returns 404. Public-API
 * shape stays hidden until the runbook is operationally in use.
 *
 * <p>The wider {@code TenantSchemaLookup#invalidateAll()} is kept
 * package-private (test-only — see Copilot review fix #2 on PR #329)
 * and is <strong>not</strong> exposed here. Operators target one
 * hospital at a time; nuking all cache entries from a REST surface
 * would be too easy a footgun.
 */
@Service
public class TenantSchemaCacheService {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaCacheService.class);
    private static final String AUDIT_ENTITY_TYPE = "HOSPITAL";

    private final SchemaTenancyProperties properties;
    private final TenantSchemaLookup lookup;
    private final AuditEventLogService auditEventLogService;
    private final UserRepository userRepository;

    public TenantSchemaCacheService(
        ObjectProvider<SchemaTenancyProperties> propertiesProvider,
        ObjectProvider<TenantSchemaLookup> lookupProvider,
        AuditEventLogService auditEventLogService,
        UserRepository userRepository
    ) {
        this.properties = propertiesProvider.getIfAvailable();
        this.lookup = lookupProvider.getIfAvailable();
        this.auditEventLogService = auditEventLogService;
        this.userRepository = userRepository;
    }

    /**
     * True when the schema-tenancy flag is on AND the lookup bean is
     * wired. When false the controller returns 404 so the endpoint
     * shape stays hidden under the default row-level topology.
     */
    public boolean isEnabled() {
        return properties != null && properties.isEnabled() && lookup != null;
    }

    /**
     * Drop one hospital from the resolver cache and emit an audit
     * event. Idempotent — invalidating a hospital not in the cache is
     * a no-op on the cache side but still emits the audit row so the
     * operator action is recorded.
     */
    public void invalidate(UUID hospitalId) {
        if (!isEnabled() || hospitalId == null) return;
        lookup.invalidate(hospitalId);
        emitAudit(hospitalId);
    }

    private void emitAudit(UUID hospitalId) {
        try {
            User caller = currentUserOrNull();
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.TENANT_SCHEMA_CACHE_INVALIDATED)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .resourceId(hospitalId.toString())
                .userId(caller != null ? caller.getId() : null)
                .userName(caller != null ? caller.getUsername() : null)
                .eventDescription(
                    "TenantSchemaLookup cache entry invalidated for hospital "
                        + hospitalId + " (schema-per-tenant cutover Step 4)")
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for schema-cache invalidate of {}: {}", hospitalId, ex.toString());
        }
    }

    /**
     * Resolve the authenticated user so the audit row is attributed
     * to the operator. Mirrors the pattern from {@code DicomProxyService}
     * (PR #349 Copilot review High): explicit user resolution beats
     * SYSTEM fallback for a super-admin action.
     */
    private User currentUserOrNull() {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null || username.isBlank()) return null;
        return userRepository.findByUsernameIgnoreCase(username).orElse(null);
    }
}
