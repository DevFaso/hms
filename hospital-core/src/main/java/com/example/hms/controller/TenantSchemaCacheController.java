package com.example.hms.controller;

import com.example.hms.security.tenant.schema.TenantSchemaCacheService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Super-admin cache-invalidation surface for the schema-per-tenant
 * cutover (roadmap row 33 follow-on, v2.0 / Multi-tenancy).
 *
 * <p>Pairs with
 * {@code docs/runbooks/schema-per-tenant-migration.md} Step 4 — after
 * the operator UPDATEs {@code hospital.hospitals.isolation_mode}, this
 * endpoint drops the cached resolver entry so the next request to that
 * hospital resolves to the new schema immediately instead of waiting
 * for the 5-min TTL to expire.
 *
 * <p>Gated by {@code app.tenancy.schema-isolation.enabled}. When the
 * flag is off, returns {@code 404 Not Found} so the endpoint shape
 * stays hidden under the default row-level topology. Authentication is
 * still enforced — anonymous requests stop at Spring Security with
 * {@code 401} ahead of the flag check.
 */
@RestController
@RequestMapping("/super-admin/tenancy")
public class TenantSchemaCacheController {

    private final TenantSchemaCacheService service;

    public TenantSchemaCacheController(TenantSchemaCacheService service) {
        this.service = service;
    }

    /**
     * POST /api/super-admin/tenancy/schema-cache/invalidate/{hospitalId}.
     * Drops one hospital from the resolver cache and emits a
     * {@code TENANT_SCHEMA_CACHE_INVALIDATED} audit row attributed to
     * the calling super-admin.
     */
    @PostMapping("/schema-cache/invalidate/{hospitalId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> invalidate(@PathVariable UUID hospitalId) {
        if (!service.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        service.invalidate(hospitalId);
        return ResponseEntity.noContent().build();
    }
}
