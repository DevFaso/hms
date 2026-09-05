package com.example.hms.db;

import com.example.hms.HmsApplication;
import com.example.hms.model.Patient;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.model.pro.ProInstrumentItem;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real application the way dev and prod boot it: Liquibase builds
 * the schema on a real PostgreSQL, then Hibernate validates every
 * {@code @Entity} against it ({@code ddl-auto=validate}).
 *
 * <p>Why: every PostgreSQL profile validates, every test profile is
 * create-drop FROM the entities — so a column an entity maps and a migration
 * forgot has never been visible to the suite. V108 → V110, V116–V118 and
 * V153 → V154 (three PRO tables extending BaseEntity without
 * created_at/updated_at) each took an environment down for exactly that.
 * {@link LiquibaseSchemaIT} proves the changelog applies; this proves the
 * model can live on what it built.
 *
 * <p>Deliberately a full {@code @SpringBootTest} rather than a hand-built
 * SessionFactory: naming strategies, {@code spring.jpa.properties.*}, the
 * entity scan root, {@code SpringBeanContainer} for Spring-constructed
 * converters ({@code TotpSecretEncryptor}) all come from the same
 * configuration the application reads, so the check cannot drift from the
 * deploy it stands in for. The {@code test} profile is kept for everything
 * that is not the database (keys, Kafka off, seeders off); only the
 * datasource, dialect, DDL mode and migration switches are overridden.
 */
@Testcontainers
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
class EntitySchemaValidationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("hms_validate")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // The three switches that make this the dev/prod boot path.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");   // schema-h2.sql is H2-only
    }

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * Reaching this method IS the assertion: with {@code ddl-auto=validate}
     * Hibernate throws {@code SchemaManagementException} on the first mapped
     * table or column the migrations did not build, and the context never
     * starts. The checks below only prove the validation covered the model.
     */
    @Test
    void everyEntityMapsOntoTheMigratedSchema() {
        Set<Class<?>> mapped = entityManagerFactory.getMetamodel().getEntities().stream()
            .map(EntityType::getJavaType)
            .collect(Collectors.toSet());
        assertThat(mapped)
            .contains(Patient.class, UserMfaEnrollment.class, ProInstrumentItem.class)
            .hasSizeGreaterThan(200);
    }
}
