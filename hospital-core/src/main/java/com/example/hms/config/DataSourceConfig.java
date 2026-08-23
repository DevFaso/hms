package com.example.hms.config;

import com.example.hms.config.datasource.ReadWriteRoutingDataSource;
import com.example.hms.config.datasource.ReplicaDataSourceProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Wires the application's JDBC data sources.
 *
 * <p>The original responsibility of this class — turning Railway-style
 * {@code postgresql://user:pass@host:port/db} into proper JDBC + separated
 * credentials — is preserved unchanged. Roadmap row 35 extends it with an
 * optional read-replica pool: when
 * {@code app.datasource.replica.enabled=true} the primary {@code DataSource}
 * bean becomes a {@link ReadWriteRoutingDataSource} that routes based on
 * Spring's {@code TransactionSynchronizationManager.isCurrentTransactionReadOnly()}.
 * When the flag is {@code false} (the default), the primary bean is the
 * single write pool — bit-for-bit unchanged from the pre-row-35 baseline.
 */
@Configuration
@EnableConfigurationProperties(ReplicaDataSourceProperties.class)
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * The write-side (primary) Hikari pool. Pre-existing behaviour;
     * extracted into a named bean so the routing wrapper can compose it.
     *
     * <p>The {@code @ConfigurationProperties("spring.datasource.hikari")}
     * annotation binds the {@code spring.datasource.hikari.*} tuning
     * keys (maximum-pool-size, connection-timeout, etc.) to the
     * returned pool — without this binding Spring Boot's auto-config
     * does not apply them when the {@code DataSource} bean is defined
     * manually.
     */
    @Bean(name = "writeDataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource writeDataSource(DataSourceProperties properties) {
        normalizeRailwayUrl(properties);
        return properties.initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
    }

    /**
     * Primary {@link DataSource} consumed by JPA, Spring JDBC, Liquibase,
     * etc. Routes between {@code writeDataSource} and (when configured)
     * the optional replica produced by
     * {@link ReadReplicaDataSourceConfiguration}.
     *
     * <p>The replica bean is optional ({@code required = false}); when
     * the replica auto-configuration is conditionally skipped (the
     * default), the routing wrapper is also skipped and this method
     * returns the write pool directly — preserving the pre-row-35
     * single-pool behaviour exactly.
     */
    @Bean
    @Primary
    public DataSource dataSource(
        @Qualifier("writeDataSource") DataSource writeDataSource,
        @Qualifier("replicaDataSource") java.util.Optional<DataSource> replicaDataSource,
        ReplicaDataSourceProperties replicaProperties
    ) {
        if (!replicaProperties.isEnabled() || replicaDataSource.isEmpty()) {
            return writeDataSource;
        }
        return new ReadWriteRoutingDataSource(writeDataSource, replicaDataSource.get());
    }

    /**
     * Translates Railway's {@code postgresql://user:pass@host:port/db}
     * into the JDBC form Spring expects, mutating
     * {@link DataSourceProperties#setUrl(String)} /
     * {@link DataSourceProperties#setUsername(String)} /
     * {@link DataSourceProperties#setPassword(String)} in place. No-op when
     * the URL is already JDBC.
     *
     * <p>Behaviour is unchanged from the pre-row-35 implementation;
     * extracted into a helper so the writeDataSource bean stays small
     * and the routing wrapper can be reasoned about in isolation.
     */
    private static void normalizeRailwayUrl(DataSourceProperties properties) {
        String url = properties.getUrl();
        if (url == null) return;
        if (!url.startsWith("postgresql://") && !url.startsWith("postgres://")) return;
        try {
            URI dbUri = new URI(url);
            String jdbcUrl = "jdbc:postgresql://" + dbUri.getHost()
                + ":" + dbUri.getPort()
                + dbUri.getPath();
            properties.setUrl(jdbcUrl);
            // indexOf rather than split(":", 2): the split form is in fact safe
            // after contains(":"), but nothing local proves parts[1] exists, so
            // it reads as an unchecked array access (and Sonar flags it as one).
            // This is provably in-bounds and allocates nothing.
            String userInfo = dbUri.getUserInfo();
            int separator = userInfo == null ? -1 : userInfo.indexOf(':');
            if (separator >= 0) {
                properties.setUsername(userInfo.substring(0, separator));
                properties.setPassword(userInfo.substring(separator + 1));
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid DATABASE_URL: " + url, e);
        }
    }
}
