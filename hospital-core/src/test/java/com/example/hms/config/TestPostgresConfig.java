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
