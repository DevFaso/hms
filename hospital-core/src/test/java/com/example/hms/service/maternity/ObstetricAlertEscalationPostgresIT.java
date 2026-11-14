package com.example.hms.service.maternity;

import com.example.hms.service.InBasketService;
import com.example.hms.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1762 on PostgreSQL, against the schema Liquibase builds (H2 builds the
 * table from the entity, without its CHECK, so only a real database shows
 * the fault): a labour transfer's urgent notice files its escalation row,
 * and the sweep then sends its reminder.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ObstetricAlertEscalationService.class, ObstetricAlertEscalationWorker.class, ObstetricAlertCurrency.class,
    StillbornNeonatalAlerts.class,
    ObstetricAlertEscalationPostgresIT.Config.class})
@DisplayName("D1762: a labour transfer's urgent notice is escalated on PostgreSQL")
class ObstetricAlertEscalationPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_obstetric_escalation")
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

    /** A clock the test moves past the escalation delay. */
    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-13T18:40:56Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        MovableClock clock() {
            return new MovableClock();
        }

        @Bean
        MessageSource messageSource() {
            StaticMessageSource source = new StaticMessageSource();
            source.setUseCodeAsDefaultMessage(true);
            return source;
        }
    }

    @MockitoBean private InBasketService inBasketService;
    @MockitoBean private NotificationService notificationService;

    @Autowired private ObstetricAlertEscalationService service;
    @Autowired private MovableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aLaborTransferNoticeIsSavedAndReminded() {
        UUID episodeId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            service.raiseAfterCommit(new ObstetricAlertEscalationService.UrgentAlert(
                hospitalId, UUID.randomUUID(), "Awa Démo", ObstetricAlertEscalationService.SOURCE_TRANSFER,
                episodeId, "TRANSFER_IN", "travail transféré ici", "Arrivée depuis l'hôpital B",
                UUID.randomUUID(), "Alerte : travail transféré ici", "labor.transfer.notice.in", List.of("B"))));

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT source_type, escalation_level FROM clinical.obstetric_alert_escalations WHERE source_id = ?",
            episodeId);
        assertThat(rows).as("the escalation row is saved").hasSize(1);
        assertThat(rows.get(0)).containsEntry("source_type", "LABOR_TRANSFER");
        assertThat(((Number) rows.get(0).get("escalation_level")).intValue()).isZero();

        clock.advance(Duration.ofMinutes(16));
        assertThat(service.escalateOverdue()).as("the sweep sends its reminder").isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT escalation_level FROM clinical.obstetric_alert_escalations WHERE source_id = ?",
            Integer.class, episodeId)).isEqualTo(1);
    }
}
