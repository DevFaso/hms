package com.example.hms.security.tenant.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Looks up a hospital's tenant isolation mode + schema name without
 * going through Hibernate. Used by {@link SchemaTenantIdentifierResolver}
 * which is itself called during Hibernate connection acquisition —
 * if we resolved through JPA we would deadlock on the entity manager
 * trying to resolve its own tenant.
 *
 * <p>The lookup is a single-row JDBC SELECT against
 * {@code hospital.hospitals} and the result is cached in-memory for
 * {@link #DEFAULT_TTL} (5 minutes) — long enough to amortize the
 * cost across thousands of requests, short enough that an isolation-mode
 * flip propagates without a process restart. Callers that mutate
 * hospital isolation state (the migration runbook procedure) should
 * call {@link #invalidate(UUID)} immediately after the COMMIT.
 *
 * <p>Bean only exists when {@code app.tenancy.schema-isolation.enabled=true}
 * (see {@link SchemaTenancyProperties}); under the default row-level
 * topology this class is dead code and Spring never instantiates it.
 */
@Component
@ConditionalOnProperty(name = "app.tenancy.schema-isolation.enabled", havingValue = "true")
public class TenantSchemaLookup {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaLookup.class);

    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private static final String SQL =
        "SELECT isolation_mode, tenant_schema_name "
            + "FROM hospital.hospitals WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final ConcurrentMap<UUID, CachedEntry> cache = new ConcurrentHashMap<>();
    private final Duration ttl;

    public TenantSchemaLookup(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), DEFAULT_TTL);
    }

    TenantSchemaLookup(JdbcTemplate jdbc, Duration ttl) {
        this.jdbc = jdbc;
        this.ttl = ttl;
    }

    /**
     * Resolve the schema name for a hospital, or {@link Optional#empty()}
     * if the hospital is row-level (or doesn't exist). Empty also means
     * "fall back to the default search_path".
     */
    public Optional<String> schemaFor(UUID hospitalId) {
        if (hospitalId == null) {
            return Optional.empty();
        }
        CachedEntry entry = cache.get(hospitalId);
        Instant now = Instant.now();
        if (entry != null && entry.expiresAt.isAfter(now)) {
            return Optional.ofNullable(entry.schemaName);
        }
        CachedEntry fresh = loadFromDb(hospitalId, now);
        cache.put(hospitalId, fresh);
        return Optional.ofNullable(fresh.schemaName);
    }

    /**
     * Drop a single hospital from the cache. Callers that mutate
     * isolation_mode / tenant_schema_name (operator runbook) must call
     * this after their transaction commits.
     */
    public void invalidate(UUID hospitalId) {
        if (hospitalId != null) {
            cache.remove(hospitalId);
        }
    }

    /** Test-only: nuke all cached entries. */
    public void invalidateAll() {
        cache.clear();
    }

    private CachedEntry loadFromDb(UUID hospitalId, Instant now) {
        try {
            return jdbc.queryForObject(SQL, (rs, rn) -> {
                String mode = rs.getString("isolation_mode");
                String schema = rs.getString("tenant_schema_name");
                String resolved = "SCHEMA".equals(mode) ? schema : null;
                return new CachedEntry(resolved, now.plus(ttl));
            }, hospitalId);
        } catch (EmptyResultDataAccessException missing) {
            log.debug("No hospital row for id {} — defaulting to row-level search_path", hospitalId);
            return new CachedEntry(null, now.plus(ttl));
        }
    }

    private record CachedEntry(String schemaName, Instant expiresAt) { }
}
