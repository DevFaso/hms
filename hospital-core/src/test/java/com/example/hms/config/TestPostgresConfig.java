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

    /**
     * Mint a per-context H2 URL so each {@code @SpringBootTest} that imports
     * this configuration gets its own in-memory database.
     *
     * <p>Why: prior to this change, every importing test pinned the URL to
     * {@code jdbc:h2:mem:testdb}. Multiple {@code @SpringBootTest} contexts
     * coexist in the same JVM (Spring's context cache + tests with distinct
     * {@code @TestPropertySource} signatures each create their own context),
     * and they all shared one H2 instance. With {@code ddl-auto=create-drop},
     * any context shutting down would drop every schema in the shared DB,
     * including ones live contexts were still querying. The race surfaced as
     * a CI-only flake on {@code MllpTcpServerIT} where the MLLP worker
     * thread queried {@code platform.mllp_allowed_senders} and got back
     * {@code Schema "platform" not found} from a sibling context's
     * {@code create-drop} shutdown — see PR #287 CI run job 75225475133.
     *
     * <p>Each Spring context now resolves its own UUID-suffixed URL exactly
     * once at context startup, so cross-context shutdowns are no-ops on this
     * context's DB. Within a context, transaction rollback / explicit
     * cleanup work exactly as before.
     */
    private static String mintUniqueH2Url() {
        String contextId = java.util.UUID.randomUUID().toString().replace("-", "");
        return String.join(
            "",
            "jdbc:h2:mem:testdb_", contextId, ";",
            "MODE=PostgreSQL;",
            "DATABASE_TO_LOWER=TRUE;",
            "DEFAULT_NULL_ORDERING=HIGH;",
            "DB_CLOSE_DELAY=-1;",
            "DB_CLOSE_ON_EXIT=FALSE"
        );
    }

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry r) {
        // Mint once per context invocation so every property reads back the
        // same URL — the supplier closure runs multiple times during property
        // resolution, but they all return this fixed value.
        String url = mintUniqueH2Url();
        r.add("spring.datasource.url", () -> url);
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
