package com.example.hms.config;

import com.example.hms.service.platform.event.NoopPlatformRegistryEventPublisher;
import com.example.hms.service.platform.event.PlatformRegistryEventPublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessagePreparator;

@TestConfiguration
public class TestPostgresConfig {

    private static final String H2_URL = String.join(
        "",
        "jdbc:h2:mem:testdb;",
        "MODE=PostgreSQL;",
        "DATABASE_TO_LOWER=TRUE;",
        "DEFAULT_NULL_ORDERING=HIGH;",
        "DB_CLOSE_DELAY=-1;",
        "DB_CLOSE_ON_EXIT=FALSE"
    );

    /**
     * NOTE on a CI flake the earlier per-context UUID variant of this method
     * (commit c08a745d, now reverted) tried — but did NOT — fix:
     *
     * <p>{@code MllpTcpServerIT} was racing a sibling {@code @SpringBootTest}'s
     * {@code create-drop} shutdown on the shared {@code jdbc:h2:mem:testdb}
     * in-memory database, surfacing as {@code Schema "platform" not found}
     * mid-query on the mllp-worker thread. The intent of the earlier
     * UUID-per-context approach was to mint a unique URL per Spring context.
     * That logic was correct in isolation, but did not move CI: the same
     * flake reproduced on the very next push (job 75227501591) with the
     * per-context UUID code in place, which means the dynamic property never
     * actually overrode {@code spring.datasource.url} at the boundary that
     * mattered for this test. The targeted fix instead lives at the test-
     * class boundary — see {@code MllpTcpServerIT}'s own
     * {@code @TestPropertySource(spring.datasource.url=…${random.uuid}…)} +
     * {@code @DirtiesContext(AFTER_CLASS)}, which Spring DOES honor and
     * which made the test green in one run locally and one in CI.
     *
     * <p>The remaining 3 importers (BaseIT, CdsHooksDiscoveryIT,
     * PrescriptionsCdsHooksIT) keep the shared {@code testdb} behavior they
     * have had since this file was added — none of them spin up a worker
     * thread that queries the DB after Spring shutdown semantics could fire,
     * so the race is invisible to them.
     */
    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> H2_URL);
        r.add("spring.datasource.username", () -> "sa");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");

        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.jpa.properties.hibernate.hbm2ddl.create_namespaces", () -> "true");

        r.add("spring.flyway.enabled", () -> false);
        r.add("spring.liquibase.enabled", () -> false);
    }

    @Bean
    @Primary
    JavaMailSender testJavaMailSender() {
        return new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                // swallow mail attempts during tests
            }

            @Override
            public void send(MimeMessage... mimeMessages) {
                // swallow bulk mail attempts during tests
            }

            @Override
            public void send(SimpleMailMessage simpleMessage) {
                // swallow simple mail attempts during tests
            }

            @Override
            public void send(SimpleMailMessage... simpleMessages) {
                // swallow simple mail attempts during tests
            }

            @Override
            public void send(MimeMessagePreparator mimeMessagePreparator) {
                try {
                    mimeMessagePreparator.prepare(createMimeMessage());
                } catch (Exception ex) {
                    throw new IllegalStateException("Failed to prepare test mail", ex);
                }
            }

            @Override
            public void send(MimeMessagePreparator... mimeMessagePreparators) {
                for (MimeMessagePreparator preparator : mimeMessagePreparators) {
                    send(preparator);
                }
            }
        };
    }

    @Bean
    @Primary
    PlatformRegistryEventPublisher testPlatformRegistryEventPublisher() {
        return new NoopPlatformRegistryEventPublisher();
    }
}
