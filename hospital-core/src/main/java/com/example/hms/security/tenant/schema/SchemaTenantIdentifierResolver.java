package com.example.hms.security.tenant.schema;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the current Hibernate "tenant identifier" from the active
 * {@link HospitalContext}. Hibernate calls this on every connection
 * acquisition so it must be cheap and non-recursive
 * (no JPA / no transactional services).
 *
 * <p>Returns:
 * <ul>
 *   <li>{@link #DEFAULT_TENANT} when there is no active hospital
 *       (Liquibase, scheduled jobs, super-admin without an active
 *       hospital pinned).</li>
 *   <li>{@link #DEFAULT_TENANT} when the active hospital is in
 *       {@code ROW_LEVEL} mode — the connection still uses the shared
 *       search_path; row-level filtering happens at the
 *       Spring Data Specification layer.</li>
 *   <li>The hospital's {@code tenant_schema_name} when the hospital
 *       is in {@code SCHEMA} mode. {@link SchemaTenantConnectionProvider}
 *       maps that string to a connection with the right search_path.</li>
 * </ul>
 *
 * <p>Bean only exists when {@code app.tenancy.schema-isolation.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.tenancy.schema-isolation.enabled", havingValue = "true")
public class SchemaTenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    /**
     * Sentinel returned for any caller that should use the shared
     * search_path. {@link SchemaTenantConnectionProvider} treats this
     * as "do not switch schemas".
     */
    public static final String DEFAULT_TENANT = "__default__";

    private final TenantSchemaLookup lookup;

    public SchemaTenantIdentifierResolver(TenantSchemaLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public String resolveCurrentTenantIdentifier() {
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID active = ctx.getActiveHospitalId();
        if (active == null) {
            return DEFAULT_TENANT;
        }
        return lookup.schemaFor(active).orElse(DEFAULT_TENANT);
    }

    /**
     * We always validate — the alternative (returning false) lets
     * Hibernate cache an EntityManager bound to a stale tenant across
     * a request boundary if a session is reused, which would silently
     * leak data across schemas. The lookup is cached so this is cheap.
     */
    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
