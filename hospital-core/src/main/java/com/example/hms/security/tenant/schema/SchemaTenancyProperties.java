package com.example.hms.security.tenant.schema;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration knobs for schema-per-tenant isolation
 * (roadmap row 33, v2.0 / Multi-tenancy).
 *
 * <p>The whole feature is gated by {@link #enabled}. Default false —
 * production deployments today are row-level multi-tenant and the
 * schema-tenancy beans (resolver, connection provider, lookup) are
 * not even instantiated when the flag is off.
 *
 * <pre>
 * app:
 *   tenancy:
 *     schema-isolation:
 *       enabled: false
 *       default-search-path:
 *         - hospital
 *         - clinical
 *         - billing
 *         - lab
 *         - reference
 *         - platform
 *         - security
 *         - support
 *         - public
 *       shared-schemas:
 *         - reference
 *         - platform
 *         - security
 *         - support
 *         - public
 * </pre>
 *
 * <p>{@link #defaultSearchPath} is what every connection sees in
 * row-level mode (or for system operations like Liquibase / scheduled
 * jobs that have no active hospital). {@link #sharedSchemas} are the
 * schemas appended to a tenant-isolated connection's search_path
 * after the dedicated tenant schema, so reference data, audit logs,
 * security tables, etc. stay accessible.
 */
@ConfigurationProperties(prefix = "app.tenancy.schema-isolation")
public class SchemaTenancyProperties {

    private boolean enabled = false;

    private List<String> defaultSearchPath = List.of(
        "hospital", "clinical", "billing", "lab",
        "reference", "platform", "security", "support", "public");

    private List<String> sharedSchemas = List.of(
        "reference", "platform", "security", "support", "public");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getDefaultSearchPath() { return defaultSearchPath; }
    public void setDefaultSearchPath(List<String> defaultSearchPath) {
        this.defaultSearchPath = List.copyOf(defaultSearchPath);
    }

    public List<String> getSharedSchemas() { return sharedSchemas; }
    public void setSharedSchemas(List<String> sharedSchemas) {
        this.sharedSchemas = List.copyOf(sharedSchemas);
    }
}
