package com.example.hms.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class RenderDatabaseEnvironmentPostProcessorTest {

    private final RenderDatabaseEnvironmentPostProcessor postProcessor = new RenderDatabaseEnvironmentPostProcessor();

    @Test
    void populatesDevPasswordFromDatabasePasswordWhenOnlyPasswordIsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("DEV_DB_URL", "jdbc:postgresql://db:5432/hms");
        environment.setProperty("DEV_DB_USERNAME", "hms_user");
        environment.setProperty("DATABASE_PASSWORD", "super-secret");

        postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("DEV_DB_PASSWORD")).isEqualTo("super-secret");
    }

    @Test
    void keepsExistingDevPasswordIfAlreadyPresent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("DEV_DB_PASSWORD", "already-set");
        environment.setProperty("DATABASE_PASSWORD", "should-not-override");

        postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("DEV_DB_PASSWORD")).isEqualTo("already-set");
    }

    @Test
    void derivesAllDevSettingsFromDatabaseUrlWhenMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(
            "DATABASE_URL",
            "postgres://render_user:r%40nd%23pass@db.internal:5432/hms_core?connect_timeout=5"
        );

        postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("DEV_DB_URL"))
            .isEqualTo("jdbc:postgresql://db.internal:5432/hms_core?connect_timeout=5&sslmode=require");
        assertThat(environment.getProperty("DEV_DB_USERNAME")).isEqualTo("render_user");
        assertThat(environment.getProperty("DEV_DB_PASSWORD")).isEqualTo("r@nd#pass");
        assertThat(environment.getProperty("DEV_DB_HOST")).isEqualTo("db.internal");
        assertThat(environment.getProperty("DEV_DB_PORT")).isEqualTo("5432");
        assertThat(environment.getProperty("DEV_DB_NAME")).isEqualTo("hms_core");
    }

    @Test
    void usesExpandedFallbacksWhenDatabaseUrlMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("SPRING_DATASOURCE_URL", "jdbc:postgresql://fallback:5432/hms");
        environment.setProperty("DB_USERNAME", "fallback_user");
        environment.setProperty("PGPASSWORD", "fallback-pass");
        environment.setProperty("DB_HOST", "fallback");
        environment.setProperty("DB_PORT", "5433");
        environment.setProperty("DB_NAME", "fallback_db");

        postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("DEV_DB_URL")).isEqualTo("jdbc:postgresql://fallback:5432/hms");
        assertThat(environment.getProperty("DEV_DB_USERNAME")).isEqualTo("fallback_user");
        assertThat(environment.getProperty("DEV_DB_PASSWORD")).isEqualTo("fallback-pass");
        assertThat(environment.getProperty("DEV_DB_HOST")).isEqualTo("fallback");
        assertThat(environment.getProperty("DEV_DB_PORT")).isEqualTo("5433");
        assertThat(environment.getProperty("DEV_DB_NAME")).isEqualTo("fallback_db");
    }

    @Test
    void skipsWorkWhenAllDevSettingsAlreadyPresent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("DEV_DB_URL", "jdbc:postgresql://existing:5432/hms");
        environment.setProperty("DEV_DB_USERNAME", "existing_user");
        environment.setProperty("DEV_DB_PASSWORD", "existing_pass");
        environment.setProperty("DEV_DB_HOST", "existing");
        environment.setProperty("DEV_DB_PORT", "5432");
        environment.setProperty("DEV_DB_NAME", "existing_db");
        environment.setProperty("DATABASE_URL", "postgres://new_user:new_pass@new-host:5433/override");

        postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("DEV_DB_USERNAME")).isEqualTo("existing_user");
        assertThat(environment.getProperty("DEV_DB_PASSWORD")).isEqualTo("existing_pass");
        assertThat(environment.getProperty("DEV_DB_URL")).isEqualTo("jdbc:postgresql://existing:5432/hms");
    }

    @Test
    void ignoresDatabaseUrlWithoutCredentials() {
        MockEnvironment environment = new MockEnvironment();
        String databaseUrl = "postgres://db-without-creds:5432/hms";
        environment.setProperty("DATABASE_URL", databaseUrl);

        postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("DEV_DB_USERNAME")).isNull();
        assertThat(environment.getProperty("DEV_DB_PASSWORD")).isNull();
        assertThat(environment.getProperty("DEV_DB_URL")).isEqualTo(databaseUrl);
    }

    @Test
    void preservesSslModeIfAlreadySpecified() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("DATABASE_URL", "postgres://user:pass@db:5432/hms?sslmode=verify-full&connect_timeout=3");

        postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("DEV_DB_URL"))
            .isEqualTo("jdbc:postgresql://db:5432/hms?sslmode=verify-full&connect_timeout=3");
    }

    @Test
    void fallsBackToExplicitPasswordWhenDatabaseUrlAbsent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("DATABASE_PASSWORD", "env-pass");

        postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("DEV_DB_PASSWORD")).isEqualTo("env-pass");
    }

    @Test
    void handlesMalformedDatabaseUrlGracefully() {
        MockEnvironment environment = new MockEnvironment();
        String databaseUrl = "postgres://:@:bad";
        environment.setProperty("DATABASE_URL", databaseUrl);

        postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("DEV_DB_URL")).isEqualTo(databaseUrl);
        assertThat(environment.getProperty("DEV_DB_USERNAME")).isNull();
    }
}
