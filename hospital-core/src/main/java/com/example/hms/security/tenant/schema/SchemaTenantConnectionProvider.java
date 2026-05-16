package com.example.hms.security.tenant.schema;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hands Hibernate a {@link Connection} pre-positioned at the right
 * PostgreSQL {@code search_path} for the active tenant.
 *
 * <p>Both physical pool exhaustion and search_path correctness are
 * the responsibility of this single class:
 * <ul>
 *   <li>The underlying connection comes from the existing application
 *       Hikari pool (single shared pool — no per-tenant pools).</li>
 *   <li>Before returning the connection to Hibernate we issue
 *       {@code SET search_path TO ...} so every query Hibernate runs
 *       in this session resolves unqualified table names against the
 *       right schemas.</li>
 *   <li>On release we restore the default search_path and hand the
 *       connection back to Hikari, so the next caller doesn't inherit
 *       a tenant-specific path.</li>
 * </ul>
 *
 * <p>The "DEFAULT" tenant identifier
 * ({@link SchemaTenantIdentifierResolver#DEFAULT_TENANT}) keeps the
 * default search_path; any other identifier is interpreted as a
 * PostgreSQL schema name and prepended to the
 * {@link SchemaTenancyProperties#getSharedSchemas() shared schemas}.
 *
 * <p>Bean only exists when {@code app.tenancy.schema-isolation.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.tenancy.schema-isolation.enabled", havingValue = "true")
public class SchemaTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    /** Strict allow-list — only PG identifier characters, ≤63 chars. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final transient DataSource dataSource;
    private final String defaultSearchPath;
    private final List<String> sharedSchemas;

    public SchemaTenantConnectionProvider(DataSource dataSource, SchemaTenancyProperties props) {
        this.dataSource = dataSource;
        this.defaultSearchPath = props.getDefaultSearchPath().stream()
            .map(SchemaTenantConnectionProvider::requireSafe)
            .collect(Collectors.joining(", "));
        this.sharedSchemas = props.getSharedSchemas().stream()
            .map(SchemaTenantConnectionProvider::requireSafe)
            .toList();
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return connectionWithSearchPath(defaultSearchPath);
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        try {
            applySearchPath(connection, defaultSearchPath);
        } finally {
            connection.close();
        }
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        return connectionWithSearchPath(searchPathFor(tenantIdentifier));
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        try {
            applySearchPath(connection, defaultSearchPath);
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return DataSource.class.isAssignableFrom(unwrapType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> unwrapType) {
        if (DataSource.class.isAssignableFrom(unwrapType)) {
            return (T) dataSource;
        }
        throw new IllegalArgumentException("Cannot unwrap to " + unwrapType.getName());
    }

    /** Build the comma-separated search_path for a given tenant. */
    String searchPathFor(String tenantIdentifier) {
        if (tenantIdentifier == null
            || SchemaTenantIdentifierResolver.DEFAULT_TENANT.equals(tenantIdentifier)) {
            return defaultSearchPath;
        }
        String safe = requireSafe(tenantIdentifier);
        StringBuilder sb = new StringBuilder(safe);
        for (String shared : sharedSchemas) {
            sb.append(", ").append(shared);
        }
        return sb.toString();
    }

    @SuppressWarnings("java:S2077") // Safe: searchPath is assembled only from requireSafe() identifiers.
    private static void applySearchPath(Connection conn, String validatedSearchPath) throws SQLException {
        // PostgreSQL doesn't accept search_path values as bind parameters,
        // so callers MUST hand us identifiers that already passed
        // requireSafe(). The regex excludes quotes, semicolons, comments,
        // whitespace, dots, and commas; only this method formats the SQL.
        try (var stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO " + validatedSearchPath);
        } catch (SQLException ex) {
            throw new SQLException("Failed to set search_path to '" + validatedSearchPath + "'", ex);
        }
    }

    private Connection connectionWithSearchPath(String searchPath) throws SQLException {
        Connection conn = dataSource.getConnection();
        try {
            applySearchPath(conn, searchPath);
            return conn;
        } catch (SQLException ex) {
            try {
                conn.close();
            } catch (SQLException closeEx) {
                ex.addSuppressed(closeEx);
            }
            throw ex;
        }
    }

    private static String requireSafe(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                "Refusing to use unsafe schema identifier: '" + identifier + "'");
        }
        return identifier;
    }
}
