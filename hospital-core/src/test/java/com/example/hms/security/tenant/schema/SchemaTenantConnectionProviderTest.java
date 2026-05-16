package com.example.hms.security.tenant.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaTenantConnectionProviderTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final SchemaTenancyProperties props = new SchemaTenancyProperties();
    private SchemaTenantConnectionProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        provider = new SchemaTenantConnectionProvider(dataSource, props);
    }

    @Test
    void buildsDefaultSearchPathFromConfiguredList() {
        String path = provider.searchPathFor(SchemaTenantIdentifierResolver.DEFAULT_TENANT);
        assertThat(path).isEqualTo(
            "hospital, clinical, billing, lab, reference, platform, security, support, public");
    }

    @Test
    void buildsTenantSearchPathWithSharedSchemasAppended() {
        String path = provider.searchPathFor("tenant_alpha");
        assertThat(path).isEqualTo(
            "tenant_alpha, reference, platform, security, support, public");
    }

    @Test
    void rejectsTenantIdentifierWithSqlInjectionAttempt() {
        assertThatThrownBy(() -> provider.searchPathFor("public; DROP TABLE patients; --"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsafe schema identifier");
    }

    @Test
    void rejectsTenantIdentifierWithUppercaseOrLeadingDigit() {
        assertThatThrownBy(() -> provider.searchPathFor("Tenant_alpha"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.searchPathFor("1tenant"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getAnyConnectionAppliesDefaultSearchPath() throws Exception {
        Connection out = provider.getAnyConnection();

        assertThat(out).isSameAs(connection);
        ArgumentCaptorAssertion.assertOneSearchPathSet(statement,
            "hospital, clinical, billing, lab, reference, platform, security, support, public");
    }

    @Test
    void getConnectionForTenantAppliesTenantSearchPath() throws Exception {
        Connection out = provider.getConnection("tenant_beta");

        assertThat(out).isSameAs(connection);
        ArgumentCaptorAssertion.assertOneSearchPathSet(statement,
            "tenant_beta, reference, platform, security, support, public");
    }

    @Test
    void getConnectionForDefaultTenantAppliesDefaultSearchPath() throws Exception {
        provider.getConnection(SchemaTenantIdentifierResolver.DEFAULT_TENANT);

        ArgumentCaptorAssertion.assertOneSearchPathSet(statement,
            "hospital, clinical, billing, lab, reference, platform, security, support, public");
    }

    @Test
    void releaseConnectionResetsToDefaultBeforeClose() throws Exception {
        provider.releaseConnection("tenant_gamma", connection);

        // Two SET search_path calls happened across the test:
        //   nothing on getConnection (we didn't call it here)
        //   one to reset on release
        verify(statement, times(1)).execute("SET search_path TO "
            + "hospital, clinical, billing, lab, reference, platform, security, support, public");
        verify(connection).close();
    }

    @Test
    void releaseAnyConnectionAlsoResetsAndCloses() throws Exception {
        provider.releaseAnyConnection(connection);

        verify(statement).execute("SET search_path TO "
            + "hospital, clinical, billing, lab, reference, platform, security, support, public");
        verify(connection).close();
    }

    @Test
    void supportsAggressiveReleaseIsFalse() {
        // Aggressive release would have Hibernate ask for / give back a
        // connection on every JDBC operation, defeating our search_path
        // setup cost. Stick with one-per-session.
        assertThat(provider.supportsAggressiveRelease()).isFalse();
    }

    @Test
    void unwrapToDataSourceReturnsUnderlyingPool() {
        assertThat(provider.isUnwrappableAs(DataSource.class)).isTrue();
        assertThat(provider.unwrap(DataSource.class)).isSameAs(dataSource);
    }

    @Test
    void unwrapToUnsupportedTypeThrows() {
        assertThatThrownBy(() -> provider.unwrap(String.class))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsConfiguredDefaultPathWithUnsafeSchema() {
        SchemaTenancyProperties bad = new SchemaTenancyProperties();
        bad.setDefaultSearchPath(List.of("hospital", "DROP TABLE x"));

        assertThatThrownBy(() -> new SchemaTenantConnectionProvider(dataSource, bad))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Tiny helper to keep the assertOneSearchPathSet pattern compact. */
    private static final class ArgumentCaptorAssertion {
        static void assertOneSearchPathSet(Statement stmt, String expectedPath) throws Exception {
            var sql = forClass(String.class);
            verify(stmt, times(1)).execute(sql.capture());
            verify(stmt, never()).executeUpdate(sql.getValue());
            assertThat(sql.getValue()).isEqualTo("SET search_path TO " + expectedPath);
        }
    }
}
