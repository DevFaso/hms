package com.example.hms.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Converts Railway-style DATABASE_URL (postgresql://user:pass@host:port/db)
 * into proper JDBC format with separated credentials.
 *
 * Railway provides: postgresql://postgres:password@host.railway.internal:5432/railway
 * JDBC needs:       jdbc:postgresql://host.railway.internal:5432/railway  + username + password
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        String url = properties.getUrl();

        if (url != null && url.startsWith("postgresql://")) {
            try {
                // Parse the URI to extract components
                URI dbUri = new URI(url);
                String userInfo = dbUri.getUserInfo();

                // Build JDBC URL without credentials
                String jdbcUrl = "jdbc:postgresql://" + dbUri.getHost()
                        + ":" + dbUri.getPort()
                        + dbUri.getPath();

                properties.setUrl(jdbcUrl);

                // Extract username and password from URI
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    properties.setUsername(parts[0]);
                    properties.setPassword(parts[1]);
                }
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Invalid DATABASE_URL: " + url, e);
            }
        } else if (url != null && url.startsWith("postgres://")) {
            // Some providers use postgres:// instead of postgresql://
            try {
                URI dbUri = new URI(url);
                String userInfo = dbUri.getUserInfo();

                String jdbcUrl = "jdbc:postgresql://" + dbUri.getHost()
                        + ":" + dbUri.getPort()
                        + dbUri.getPath();

                properties.setUrl(jdbcUrl);

                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    properties.setUsername(parts[0]);
                    properties.setPassword(parts[1]);
                }
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Invalid DATABASE_URL: " + url, e);
            }
        }

        return properties.initializeDataSourceBuilder().build();
    }
}
