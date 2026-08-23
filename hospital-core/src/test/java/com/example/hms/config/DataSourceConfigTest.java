package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Credential parsing for Railway-style {@code postgresql://user:pass@host/db}
 * URLs. This ran on every boot in the deployed environments and had no tests.
 */
class DataSourceConfigTest {

    private static DataSourceProperties propertiesWithUrl(String url) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(url);
        return properties;
    }

    @Test
    @DisplayName("splits user and password out of a postgresql:// URL")
    void translatesPostgresqlUrl() {
        DataSourceProperties properties =
            propertiesWithUrl("postgresql://hms_app:s3cret@db.internal:5432/hms");

        DataSourceConfig.normalizeRailwayUrl(properties);

        assertThat(properties.getUrl()).isEqualTo("jdbc:postgresql://db.internal:5432/hms");
        assertThat(properties.getUsername()).isEqualTo("hms_app");
        assertThat(properties.getPassword()).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("accepts the postgres:// spelling too")
    void translatesPostgresUrl() {
        DataSourceProperties properties =
            propertiesWithUrl("postgres://hms_app:s3cret@db.internal:5432/hms");

        DataSourceConfig.normalizeRailwayUrl(properties);

        assertThat(properties.getUrl()).isEqualTo("jdbc:postgresql://db.internal:5432/hms");
        assertThat(properties.getUsername()).isEqualTo("hms_app");
    }

    @Test
    @DisplayName("a password containing colons survives — only the first splits")
    void splitsOnTheFirstColonOnly() {
        // The whole point of parsing at indexOf(':') rather than a naive split:
        // ':' is legal inside a password.
        DataSourceProperties properties =
            propertiesWithUrl("postgresql://hms_app:pa:ss:word@db.internal:5432/hms");

        DataSourceConfig.normalizeRailwayUrl(properties);

        assertThat(properties.getUsername()).isEqualTo("hms_app");
        assertThat(properties.getPassword()).isEqualTo("pa:ss:word");
    }

    @Test
    @DisplayName("an empty password is kept as empty, not dropped")
    void handlesTrailingColon() {
        DataSourceProperties properties =
            propertiesWithUrl("postgresql://hms_app:@db.internal:5432/hms");

        DataSourceConfig.normalizeRailwayUrl(properties);

        assertThat(properties.getUsername()).isEqualTo("hms_app");
        assertThat(properties.getPassword()).isEmpty();
    }

    @Test
    @DisplayName("credentials are left alone when the URL carries none")
    void leavesCredentialsUntouchedWithoutUserInfo() {
        DataSourceProperties properties = propertiesWithUrl("postgresql://db.internal:5432/hms");
        properties.setUsername("from-env");

        DataSourceConfig.normalizeRailwayUrl(properties);

        assertThat(properties.getUrl()).isEqualTo("jdbc:postgresql://db.internal:5432/hms");
        assertThat(properties.getUsername()).isEqualTo("from-env");
    }

    @Test
    @DisplayName("a JDBC URL is left exactly as it is")
    void isNoOpForJdbcUrl() {
        DataSourceProperties properties = propertiesWithUrl("jdbc:postgresql://localhost:5432/hms");

        DataSourceConfig.normalizeRailwayUrl(properties);

        assertThat(properties.getUrl()).isEqualTo("jdbc:postgresql://localhost:5432/hms");
    }

    @Test
    @DisplayName("a null URL is a no-op, not a crash")
    void isNoOpForNullUrl() {
        DataSourceProperties properties = propertiesWithUrl(null);

        DataSourceConfig.normalizeRailwayUrl(properties);

        assertThat(properties.getUrl()).isNull();
    }

    @Test
    @DisplayName("a malformed URL fails loudly, naming the offending value")
    void rejectsMalformedUrl() {
        // An illegal character is needed to make java.net.URI actually throw.
        // Note "postgresql://host:not-a-port/db" does NOT: URI treats it as a
        // registry-based authority, so getHost() is null and getPort() is -1
        // and the result is a nonsense-but-silent "jdbc:postgresql://null:-1/db".
        DataSourceProperties properties = propertiesWithUrl("postgresql://ho st:5432/db");

        assertThatThrownBy(() -> DataSourceConfig.normalizeRailwayUrl(properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid DATABASE_URL");
    }
}
