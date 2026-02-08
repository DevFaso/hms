package com.example.hms.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Derives {@code DEV_DB_*} datasource properties from Render's {@code DATABASE_URL} if individual
 * variables were not explicitly provided. This protects deploys from failing when the password or
 * JDBC URL is omitted in the service configuration but Render still exposes a consolidated
 * connection string.
 */
public class RenderDatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Log log = LogFactory.getLog(RenderDatabaseEnvironmentPostProcessor.class);
    private static final String PROPERTY_SOURCE_NAME = "renderDatabaseOverrides";
    private static final String DATABASE_URL_PROPERTY = "DATABASE_URL";
    private static final String DEV_DB_URL = "DEV_DB_URL";
    private static final String DEV_DB_USERNAME = "DEV_DB_USERNAME";
    private static final String DEV_DB_PASSWORD = "DEV_DB_PASSWORD";
    private static final String DEV_DB_HOST = "DEV_DB_HOST";
    private static final String DEV_DB_PORT = "DEV_DB_PORT";
    private static final String DEV_DB_NAME = "DEV_DB_NAME";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = environment.getProperty(DATABASE_URL_PROPERTY);
        DatabaseRequirementState missingState = new DatabaseRequirementState(
            isBlank(environment.getProperty(DEV_DB_URL)),
            isBlank(environment.getProperty(DEV_DB_USERNAME)),
            isBlank(environment.getProperty(DEV_DB_PASSWORD)),
            isBlank(environment.getProperty(DEV_DB_HOST)),
            isBlank(environment.getProperty(DEV_DB_PORT)),
            isBlank(environment.getProperty(DEV_DB_NAME))
        );

        if (missingState.fullyConfigured()) {
            return;
        }

        try {
            Map<String, Object> overrides = new LinkedHashMap<>();
            boolean derivedFromDatabaseUrl = false;
            if (!isBlank(databaseUrl)) {
                Map<String, Object> urlOverrides = buildOverrides(databaseUrl, missingState);
                overrides.putAll(urlOverrides);
                derivedFromDatabaseUrl = !urlOverrides.isEmpty();
            }
            mergeExplicitFallbacks(overrides, environment, missingState);
            if (!overrides.isEmpty()) {
                environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, overrides));
                if (log.isInfoEnabled()) {
                    String source = derivedFromDatabaseUrl
                        ? DATABASE_URL_PROPERTY
                        : "Render environment variables";
                    log.info("Populated DEV_DB settings from " + source + " for deployment.");
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to derive DEV_DB settings from DATABASE_URL", ex);
        }
    }

    private Map<String, Object> buildOverrides(String databaseUrl, DatabaseRequirementState missingState) {
        URI uri = URI.create(databaseUrl);
        String userInfo = uri.getUserInfo();
        if (isBlank(userInfo) || !userInfo.contains(":")) {
            if (log.isWarnEnabled()) {
                log.warn("DATABASE_URL does not contain username and password information.");
            }
            return Map.of();
        }

        String[] credentials = userInfo.split(":", 2);
        String rawUsername = decode(credentials[0]);
        String rawPassword = credentials.length > 1 ? decode(credentials[1]) : "";

        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String database = uri.getPath();
        if (database != null && database.startsWith("/")) {
            database = database.substring(1);
        }

        String query = uri.getQuery();
        StringBuilder queryBuilder = new StringBuilder();
        if (!isBlank(query)) {
            queryBuilder.append(query);
            if (!query.endsWith("&")) {
                queryBuilder.append('&');
            }
        }
        if (!queryContainsSslMode(query)) {
            queryBuilder.append("sslmode=require");
        } else if (!queryBuilder.isEmpty() && queryBuilder.charAt(queryBuilder.length() - 1) == '&') {
            queryBuilder.deleteCharAt(queryBuilder.length() - 1);
        }

        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
        if (!queryBuilder.isEmpty()) {
            jdbcUrl = jdbcUrl + '?' + queryBuilder;
        }

        Map<String, Object> overrides = new LinkedHashMap<>();
        if (missingState.urlMissing && !isBlank(jdbcUrl)) {
            overrides.put(DEV_DB_URL, jdbcUrl);
        }
        if (missingState.usernameMissing && !isBlank(rawUsername)) {
            overrides.put(DEV_DB_USERNAME, rawUsername);
        }
        if (missingState.passwordMissing && !isBlank(rawPassword)) {
            overrides.put(DEV_DB_PASSWORD, rawPassword);
        }

        if (missingState.hostMissing && !isBlank(host)) {
            overrides.put(DEV_DB_HOST, host);
        }
        if (missingState.portMissing) {
            overrides.put(DEV_DB_PORT, String.valueOf(port));
        }
        if (missingState.nameMissing && !isBlank(database)) {
            overrides.put(DEV_DB_NAME, database);
        }

        return overrides;
    }

    /**
     * Second-phase fallback that copies {@code DEV_DB_*} settings from alternative environment
     * variables when parsing {@code DATABASE_URL} did not populate a value (or the URL was not
     * provided). This allows deploys that rely on Render/Heroku style discrete credentials such as
     * {@code DATABASE_USERNAME}, {@code DB_USERNAME}, {@code SPRING_DATASOURCE_USERNAME}, etc. to
     * remain functional even when the consolidated connection string is missing.
     *
     * @param overrides mutable map of overrides accumulated for DEV datasource properties
     * @param environment active Spring environment that holds candidate properties
     * @param missingUrl whether {@code DEV_DB_URL} still needs a value
     * @param missingUser whether {@code DEV_DB_USERNAME} still needs a value
     * @param missingPassword whether {@code DEV_DB_PASSWORD} still needs a value
     * @param missingHost whether {@code DEV_DB_HOST} still needs a value
     * @param missingPort whether {@code DEV_DB_PORT} still needs a value
     * @param missingName whether {@code DEV_DB_NAME} still needs a value
     */
    private void mergeExplicitFallbacks(
        Map<String, Object> overrides,
        ConfigurableEnvironment environment,
        DatabaseRequirementState missingState
    ) {
        copyWhenMissing(
            overrides,
            missingState.urlMissing,
            environment,
            DEV_DB_URL,
            "JDBC_DATABASE_URL",
            "SPRING_DATASOURCE_URL",
            DATABASE_URL_PROPERTY,
            "DATABASE_JDBC_URL",
            "DATABASE_INTERNAL_URL"
        );
        copyWhenMissing(
            overrides,
            missingState.usernameMissing,
            environment,
            DEV_DB_USERNAME,
            "SPRING_DATASOURCE_USERNAME",
            "DATABASE_USERNAME",
            "DATABASE_USER",
            "DB_USERNAME",
            "DB_USER"
        );
        copyWhenMissing(
            overrides,
            missingState.passwordMissing,
            environment,
            DEV_DB_PASSWORD,
            "SPRING_DATASOURCE_PASSWORD",
            "DATABASE_PASSWORD",
            "DATABASE_PASS",
            "DB_PASSWORD",
            "DB_PASS",
            "PGPASSWORD"
        );
        copyWhenMissing(overrides, missingState.hostMissing, environment, DEV_DB_HOST, "DATABASE_HOST", "DB_HOST");
        copyWhenMissing(overrides, missingState.portMissing, environment, DEV_DB_PORT, "DATABASE_PORT", "DB_PORT");
        copyWhenMissing(overrides, missingState.nameMissing, environment, DEV_DB_NAME, "DATABASE_NAME", "DB_NAME");
    }

    private record DatabaseRequirementState(
        boolean urlMissing,
        boolean usernameMissing,
        boolean passwordMissing,
        boolean hostMissing,
        boolean portMissing,
        boolean nameMissing
    ) {

        boolean fullyConfigured() {
            return !urlMissing && !usernameMissing && !passwordMissing && !hostMissing && !portMissing && !nameMissing;
        }
    }

    /**
     * Copies the first non-blank value from {@code sourceKeys} into {@code targetKey} when the
     * corresponding DEV datasource property is missing. Keys are evaluated in the declared order,
     * which enforces the desired precedence among possible Render/Heroku/Spring environment
     * variables.
     *
     * @param overrides mutable override map that collects computed DEV datasource values
     * @param missingTarget indicates whether the target property still needs a value
     * @param environment active Spring environment that provides fallback properties
     * @param targetKey DEV datasource property key to populate
     * @param sourceKeys ordered list of candidate environment property names to evaluate
     */
    private void copyWhenMissing(
        Map<String, Object> overrides,
        boolean missingTarget,
        ConfigurableEnvironment environment,
        String targetKey,
        String... sourceKeys
    ) {
        if (!missingTarget || overrides.containsKey(targetKey)) {
            return;
        }
        for (String sourceKey : sourceKeys) {
            String candidate = environment.getProperty(sourceKey);
            if (!isBlank(candidate)) {
                overrides.put(targetKey, candidate);
                break;
            }
        }
    }

    private static boolean queryContainsSslMode(String query) {
        return query != null && query.contains("sslmode=");
    }

    private static String decode(String value) {
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
