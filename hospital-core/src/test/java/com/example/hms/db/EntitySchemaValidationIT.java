package com.example.hms.db;

import com.example.hms.HmsApplication;
import com.example.hms.model.Patient;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.model.pro.ProInstrumentItem;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
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

    @Autowired
    private JdbcTemplate jdbc;

    /** A CHECK on one column that only lists allowed values: {@code col = ANY (ARRAY['A', ...])}. */
    private static final Pattern ALLOWED_LIST = Pattern.compile("=\\s*ANY\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL = Pattern.compile("'([^']*)'");

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

    /**
     * QA D1762, the class: a CHECK constraint written for an enum-like column
     * drifts when the code gains a value and no migration widens the CHECK.
     * H2 builds tables from the entities, without those CHECKs, so the first
     * failure is in production. For every enum attribute stored by name, the
     * newest CHECK on its column (as Liquibase left it) must allow every
     * constant of the enum.
     */
    @Test
    void everyEnumColumnCheckAllowsEveryConstant() {
        Map<String, Set<String>> allowedByColumn = singleColumnAllowedLists();
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        List<String> drift = new ArrayList<>();
        int checked = 0;
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            EntityPersister persister = sessionFactory.getMappingMetamodel()
                .getEntityDescriptor(entity.getJavaType());
            List<String[]> columns = new ArrayList<>();
            persister.forEachSelectable((index, selectable) -> {
                JdbcMapping mapping = selectable.getJdbcMapping();
                Class<?> javaType = mapping.getJavaTypeDescriptor().getJavaTypeClass();
                if (javaType == null || !javaType.isEnum() || mapping.getValueConverter() != null
                    || !isCharacter(mapping.getJdbcType().getDdlTypeCode())) {
                    return;
                }
                columns.add(new String[]{selectable.getContainingTableExpression(),
                    selectable.getSelectionExpression(), javaType.getName()});
            });
            for (String[] column : columns) {
                Set<String> allowed = allowedByColumn.get(key(column[0], column[1]));
                if (allowed == null) {
                    continue;
                }
                checked++;
                Set<String> missing = new TreeSet<>();
                for (Object constant : enumConstants(column[2])) {
                    String name = ((Enum<?>) constant).name();
                    if (!allowed.contains(name)) {
                        missing.add(name);
                    }
                }
                if (!missing.isEmpty()) {
                    drift.add(column[0] + "." + column[1] + " (" + column[2] + ") rejects " + missing);
                }
            }
        }
        assertThat(checked).as("enum columns with an allowed-values CHECK").isPositive();
        assertThat(drift).as("enum values the database CHECK would refuse").isEmpty();
    }

    private Map<String, Set<String>> singleColumnAllowedLists() {
        Map<String, Set<String>> result = new HashMap<>();
        jdbc.query("""
            SELECT n.nspname, c.relname, a.attname, pg_get_constraintdef(con.oid)
              FROM pg_constraint con
              JOIN pg_class c ON c.oid = con.conrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = con.conkey[1]
             WHERE con.contype = 'c' AND cardinality(con.conkey) = 1
            """, rs -> {
                String def = rs.getString(4);
                String upper = def.toUpperCase(Locale.ROOT);
                if (!ALLOWED_LIST.matcher(def).find() || upper.contains("<>") || upper.contains("NOT ")
                    || upper.contains(" AND ")) {
                    return;
                }
                Set<String> values = new LinkedHashSet<>();
                Matcher literal = LITERAL.matcher(def);
                while (literal.find()) {
                    values.add(literal.group(1));
                }
                // Two CHECKs on one column must both pass: keep the intersection.
                result.merge(key(rs.getString(1) + "." + rs.getString(2), rs.getString(3)), values, (a, b) -> {
                    Set<String> both = new LinkedHashSet<>(a);
                    both.retainAll(b);
                    return both;
                });
            });
        return result;
    }

    private static String key(String table, String column) {
        String qualified = table.replace("\"", "").toLowerCase(Locale.ROOT);
        if (!qualified.contains(".")) {
            qualified = "public." + qualified;
        }
        return qualified + "#" + column.replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isCharacter(int ddlTypeCode) {
        return ddlTypeCode == Types.VARCHAR || ddlTypeCode == Types.CHAR || ddlTypeCode == Types.LONGVARCHAR
            || ddlTypeCode == Types.NVARCHAR || ddlTypeCode == Types.NCHAR
            || ddlTypeCode == org.hibernate.type.SqlTypes.ENUM;
    }

    private static Object[] enumConstants(String className) {
        try {
            return Class.forName(className).getEnumConstants();
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
