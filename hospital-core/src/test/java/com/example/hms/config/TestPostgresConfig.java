package com.example.hms.config;

import com.example.hms.service.platform.event.NoopPlatformRegistryEventPublisher;
import com.example.hms.service.platform.event.PlatformRegistryEventPublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessagePreparator;

@TestConfiguration
public class TestPostgresConfig {

    // Deliberately NO datasource properties here. This class used to carry a
    // @DynamicPropertySource pinning every importer to one shared
    // jdbc:h2:mem:testdb. Spring never applied it (@DynamicPropertySource is
    // only discovered on the test class and its enclosing classes, not on an
    // @Import-ed configuration), and that is the only reason it was harmless:
    // the test JVM caps its context cache (spring.properties), so contexts close
    // mid-run, and a closing context's create-drop drops the tables in its
    // database. Shared by the classes that import this (21 directly, plus
    // BaseIT's 13 subclasses), the first eviction would drop the schema under
    // every live context (measured: 'Schema "hospital" not found').
    // Each context gets its own database from spring.datasource.url in
    // application-test.yml; do not add a fixed URL back here.

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
