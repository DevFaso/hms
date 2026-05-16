package com.example.hms.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the optional read-replica {@link HikariDataSource} bean —
 * activated only when {@code app.datasource.replica.enabled=true}.
 *
 * <p>Kept in a separate {@code @Configuration} so the bean simply isn't
 * created in the default deployment configuration; the
 * {@code dataSource()} method in {@link com.example.hms.config.DataSourceConfig}
 * injects it through an {@code Optional} so the absence is unproblematic.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "app.datasource.replica",
    name = "enabled",
    havingValue = "true"
)
public class ReadReplicaDataSourceConfiguration {

    /**
     * The read-only Hikari pool. See
     * {@link ReplicaDataSourceProperties#buildReplicaDataSource()} for the
     * full set of tuning knobs (URL, credentials, pool sizing, lifetime,
     * leak detection). The bean is marked
     * {@code destroyMethod = "close"} so Spring shuts the pool down
     * cleanly during application context teardown.
     */
    @Bean(name = "replicaDataSource", destroyMethod = "close")
    public HikariDataSource replicaDataSource(ReplicaDataSourceProperties props) {
        return props.buildReplicaDataSource();
    }
}
