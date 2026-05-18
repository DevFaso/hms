package com.example.hms.controller;

import com.example.hms.security.tenant.schema.TenantProvisioningService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Super-admin REST surface for the schema-per-tenant provisioning
 * step (roadmap row 33 follow-on, "Provisioning script wiring").
 *
 * <p>Pairs with the existing {@link TenantSchemaCacheController} —
 * the cache-invalidate is Step 4 of the cutover runbook (after the
 * isolation_mode flip); this controller is the new Step 0 / 1
 * (create the empty schema + grant the runtime role usage on it,
 * BEFORE the operator runs copy-rows.sh / flips isolation_mode).
 *
 * <p>Gated by {@code app.tenancy.provisioning.enabled}. When the
 * flag is off the endpoint returns {@code 404 Not Found}. Anonymous
 * requests still stop at Spring Security with {@code 401} ahead of
 * the flag check.
 */
@RestController
@RequestMapping("/super-admin/tenancy")
public class TenantProvisioningController {

    private final TenantProvisioningService service;

    public TenantProvisioningController(TenantProvisioningService service) {
        this.service = service;
    }

    /**
     * POST /api/super-admin/tenancy/provision/{hospitalId}?schemaName=tenant_xxx
     *
     * <p>Returns 200 with the resolved schema name + a runbook
     * pointer for the next cutover step. Errors:
     * <ul>
     *   <li>404 — provisioning flag off</li>
     *   <li>400 — schemaName fails the SAFE_IDENTIFIER allowlist or
     *       the hospital UUID does not resolve</li>
     *   <li>409 — hospital already in SCHEMA isolation mode (idempotent
     *       re-call is rejected to prevent an operator from replacing
     *       a live tenant's schema with an empty one)</li>
     * </ul>
     */
    @PostMapping("/provision/{hospitalId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> provision(
        @PathVariable UUID hospitalId,
        @RequestParam("schemaName") String schemaName
    ) {
        if (!service.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        try {
            String resolvedSchema = service.provision(hospitalId, schemaName);
            return ResponseEntity.ok(Map.of(
                "hospitalId", hospitalId.toString(),
                "schemaName", resolvedSchema,
                "schemaIsolationRuntimeFlag", service.isSchemaIsolationRuntimeFlagOn(),
                "nextStep",
                    "Run scripts/tenancy/copy-rows.sh against this hospital,"
                        + " then UPDATE hospital.hospitals SET isolation_mode='SCHEMA',"
                        + " tenant_schema_name='" + resolvedSchema + "' WHERE id='" + hospitalId + "',"
                        + " then POST /super-admin/tenancy/schema-cache/invalidate/" + hospitalId
            ));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }
}
