package com.example.hms.repository;

import com.example.hms.model.Patient;
import com.example.hms.repository.support.TenantAwareJpaRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1253 (round 26), the class on the real engine: every read-only
 * {@code @Query} of every repository that takes a text or date-time
 * parameter is run on PostgreSQL (schema built by the changelog) with each
 * such parameter null - the value a search screen sends for a field left
 * empty. A null handed to a string function reached PostgreSQL as bytea and
 * failed the whole statement ("function character_length(bytea) does not
 * exist", "function lower(bytea) does not exist"), which H2 never shows.
 * Only the engine's grammar/type refusals fail the test: what a query
 * returns for nulls is each caller's business, and other exceptions (a
 * single-result method finding nothing, say) are not this class.
 *
 * <p>{@link StringFunctionNullParameterGuardTest} keeps the source honest;
 * this proves the cast form runs.
 */
@Testcontainers
@TenantScopedDataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RepositoryNullTextParameterPostgresIT.JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepositoryNullTextParameterPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_null_text_params")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired private ApplicationContext context;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void globalView() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("QA D1253: every read-only query with a text or date-time parameter runs on PostgreSQL with it null")
    void everyQueryRunsWithItsTextParametersNull() {
        List<String> refused = new ArrayList<>();
        int run = 0;
        for (Class<?> repository : StringFunctionNullParameterGuardTest.repositoryInterfaces()) {
            Object bean = context.getBeanProvider(repository).getIfAvailable();
            if (bean == null) {
                continue;
            }
            for (Method method : repository.getDeclaredMethods()) {
                if (!runsHere(method)) {
                    continue;
                }
                run++;
                String failure = invoke(bean, method);
                if (failure != null) {
                    refused.add(repository.getSimpleName() + "#" + method.getName() + ": " + failure);
                }
            }
        }
        assertThat(run).as("the scan must reach the text-parameter queries").isGreaterThan(100);
        assertThat(refused).isEmpty();
    }

    private static boolean runsHere(Method method) {
        Query query = method.getAnnotation(Query.class);
        if (query == null || method.isAnnotationPresent(Modifying.class) || method.isDefault()) {
            return false;
        }
        for (Class<?> type : method.getParameterTypes()) {
            if (type == String.class || Temporal.class.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    private String invoke(Object bean, Method method) {
        Object[] args = new Object[method.getParameterCount()];
        Type[] generic = method.getGenericParameterTypes();
        for (int i = 0; i < args.length; i++) {
            args[i] = argument(method.getParameterTypes()[i], generic[i]);
        }
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                status.setRollbackOnly();
                call(bean, method, args);
            });
            return null;
        } catch (InvalidDataAccessResourceUsageException e) {
            return rootMessage(e);
        } catch (RuntimeException e) {
            return grammarRefusal(e);
        }
    }

    private static void call(Object bean, Method method, Object[] args) {
        try {
            method.invoke(bean, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A PostgreSQL type refusal wrapped in another exception, or null when the failure is not this class. */
    private static String grammarRefusal(Throwable e) {
        String root = rootMessage(e);
        return root.contains("does not exist") || root.contains("could not determine data type") ? root : null;
    }

    private static Object argument(Class<?> type, Type generic) {
        if (type == String.class || Temporal.class.isAssignableFrom(type) || type == Boolean.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == Integer.class) {
            return 1;
        }
        if (type == long.class || type == Long.class) {
            return 1L;
        }
        if (type == BigDecimal.class) {
            return BigDecimal.ONE;
        }
        if (type == UUID.class) {
            return UUID.randomUUID();
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (Pageable.class.isAssignableFrom(type)) {
            return PageRequest.of(0, 1);
        }
        if (type == Sort.class) {
            return Sort.unsorted();
        }
        if (Collection.class.isAssignableFrom(type)) {
            Object element = generic instanceof ParameterizedType p && p.getActualTypeArguments()[0] instanceof Class<?> c
                ? sample(c) : "x";
            return Set.class.isAssignableFrom(type) ? Set.of(element) : List.of(element);
        }
        return null;
    }

    private static Object sample(Class<?> element) {
        if (element == String.class) {
            return "x";
        }
        Object value = argument(element, element);
        return value == null ? "x" : value;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage()).lines().findFirst().orElse("");
    }

    @Configuration
    @EnableJpaRepositories(basePackages = "com.example.hms.repository",
        repositoryBaseClass = TenantAwareJpaRepository.class)
    @EntityScan(basePackageClasses = Patient.class)
    static class JpaConfig {
    }
}
