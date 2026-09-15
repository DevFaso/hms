package com.example.hms.db;

import com.example.hms.HmsApplication;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.PermissionMatrixAuditEventRepository;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Boots the real context on PostgreSQL and runs the {@code (:date IS NULL OR col >= :date)}
 * predicates the portal's search screens send with the dates left empty.
 *
 * <p>Why: on Hibernate 6.6 a temporal parameter standing alone in {@code :p IS NULL} reached
 * PostgreSQL as {@code cast(? as timestamp(6)) is null}. Hibernate 7 renders a bare {@code ?},
 * and pgjdbc sends timestamps with an unspecified type so the server can coerce them - fine in
 * {@code col >= ?}, refused in {@code ? is null}: "could not determine data type of parameter".
 * Strings, UUIDs, booleans and enums are sent typed and never hit it. The predicates now cast
 * the parameter themselves ({@code CAST(:p AS LocalDateTime)}), which renders the same SQL 6.6
 * produced; {@link com.example.hms.repository.TemporalNullPredicateGuardTest} keeps new ones
 * honest on H2, and this test proves the SQL on the real engine. The first sign was every
 * super-admin search screen on dev answering 500 the day Spring Boot 4 shipped (2026-09-13).
 */
@Testcontainers
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
class JavaTimeParameterBindingIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("hms_binding")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");   // schema-h2.sql is H2-only
    }

    @Autowired
    private IntegrationMessageEventRepository integrationMessageEventRepository;
    @Autowired
    private AuditEventLogRepository auditEventLogRepository;
    @Autowired
    private BillingInvoiceRepository billingInvoiceRepository;
    @Autowired
    private PermissionMatrixAuditEventRepository permissionMatrixAuditEventRepository;

    @Test
    void theMessageLogSearchAcceptsEmptyDates() {
        assertThatCode(() -> integrationMessageEventRepository.search(null, null, null, null, null, PageRequest.of(0, 5)))
            .doesNotThrowAnyException();
        assertThatCode(() -> integrationMessageEventRepository.search(
                null, null, null, LocalDateTime.of(2026, 1, 1, 0, 0), null, PageRequest.of(0, 5)))
            .doesNotThrowAnyException();
    }

    @Test
    void theAuditDateRangeAcceptsEmptyDates() {
        assertThatCode(() -> auditEventLogRepository.findByDateRange(null, null, PageRequest.of(0, 5)))
            .doesNotThrowAnyException();
        assertThatCode(() -> auditEventLogRepository.findByDateRange(
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 0, 0), PageRequest.of(0, 5)))
            .doesNotThrowAnyException();
    }

    @Test
    void anInstantRangeAcceptsEmptyBounds() {
        assertThatCode(() -> permissionMatrixAuditEventRepository.findInDateRangeOrdered(null, null, PageRequest.of(0, 5)))
            .doesNotThrowAnyException();
        assertThatCode(() -> permissionMatrixAuditEventRepository.countInDateRange(Instant.parse("2026-01-01T00:00:00Z"), null))
            .doesNotThrowAnyException();
    }

    @Test
    void aLocalDateRangeAcceptsEmptyBounds() {
        assertThatCode(() -> billingInvoiceRepository.findAllWithFilters(
                null, null, List.of(InvoiceStatus.PAID), null, null, PageRequest.of(0, 5)))
            .doesNotThrowAnyException();
        assertThatCode(() -> billingInvoiceRepository.findAllWithFilters(
                null, null, List.of(InvoiceStatus.PAID), LocalDate.of(2026, 1, 1), null, PageRequest.of(0, 5)))
            .doesNotThrowAnyException();
    }
}
