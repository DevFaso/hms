package com.example.hms.security.tenant.schema;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Wires Hibernate's schema-per-tenant strategy when
 * {@code app.tenancy.schema-isolation.enabled=true}. Default is OFF
 * — under the default row-level multi-tenancy this configuration is
 * inert and Hibernate uses the standard single-tenant DataSource path.
 *
 * <p>When enabled, the {@link HibernatePropertiesCustomizer} pushes
 * three settings into the entity-manager factory:
 * <ul>
 *   <li>{@link MultiTenancySettings#MULTI_TENANT_CONNECTION_PROVIDER}
 *       → our {@link SchemaTenantConnectionProvider} instance.</li>
 *   <li>{@link MultiTenancySettings#MULTI_TENANT_IDENTIFIER_RESOLVER}
 *       → our {@link SchemaTenantIdentifierResolver} instance.</li>
 *   <li>{@code hibernate.multiTenancy} → {@code SCHEMA} (Hibernate 6
 *       no longer uses an enum constant for this; the string value
 *       is what it parses).</li>
 * </ul>
 *
 * <p>Hibernate ignores any DataSource bound to the EMF when a
 * connection provider is set, so the application's existing Hikari
 * pool is reached only through {@link SchemaTenantConnectionProvider}.
 * Liquibase still uses the raw DataSource directly (its bean isn't
 * routed through Hibernate), so migrations continue to run against
 * the shared schemas as expected.
 */
@Configuration
@EnableConfigurationProperties(SchemaTenancyProperties.class)
@ConditionalOnProperty(name = "app.tenancy.schema-isolation.enabled", havingValue = "true")
public class SchemaTenancyConfiguration {

    @Bean
    public HibernatePropertiesCustomizer schemaTenancyHibernateCustomizer(
            SchemaTenantConnectionProvider connectionProvider,
            SchemaTenantIdentifierResolver identifierResolver) {
        return properties -> properties.putAll(Map.of(
            MultiTenancySettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider,
            MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, identifierResolver,
            "hibernate.multiTenancy", "SCHEMA"
        ));
    }
}
