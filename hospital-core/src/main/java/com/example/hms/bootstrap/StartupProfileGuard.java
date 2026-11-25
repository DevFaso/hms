package com.example.hms.bootstrap;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Refuses to start the application on a profile set that is not deliberate.
 *
 * <ul>
 *   <li>No active profile: there is no default. Every environment names its profile
 *       ({@code SPRING_PROFILES_ACTIVE=dev}, {@code prod}, or {@code local-h2} / {@code local} on a
 *       developer machine).</li>
 *   <li>A developer profile ({@code local}, {@code local-h2}) together with a deployed one
 *       ({@code dev}, {@code prod}, …): the developer profile would bring its committed
 *       credentials and seeders into a shared environment.</li>
 *   <li>A developer or test profile on a hosted platform (Railway sets {@code RAILWAY_PROJECT_ID} /
 *       {@code RAILWAY_ENVIRONMENT_NAME} on every service): both carry committed credentials.</li>
 * </ul>
 *
 * <p>Runs after the config-data post-processor, so the active profiles are final. Registered in
 * {@code META-INF/spring.factories}, so it runs for every start, tests included (the build sets the
 * {@code test} profile).
 */
public class StartupProfileGuard implements EnvironmentPostProcessor, Ordered {

    /** Variables a hosted platform sets on every service it runs. */
    static final List<String> HOSTED_PLATFORM_MARKERS = List.of("RAILWAY_PROJECT_ID", "RAILWAY_ENVIRONMENT_NAME");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        check(environment);
    }

    static void check(Environment environment) {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            throw new StartupConfigurationException(
                "No Spring profile is active, and the application has no default. Set SPRING_PROFILES_ACTIVE "
                    + "(dev or prod on a deployed service; local-h2 or local on a developer machine).");
        }
        if (DeploymentProfiles.anyDeveloper(active) && DeploymentProfiles.anyDeployed(active)) {
            throw new StartupConfigurationException(
                "Profiles " + Arrays.toString(active) + " mix a developer profile (local, local-h2) with a "
                    + "deployed one. A developer profile carries committed credentials and seeds data; "
                    + "activate only the deployed profile.");
        }
        if (DeploymentProfiles.anyWithCommittedCredentials(active)) {
            for (String marker : HOSTED_PLATFORM_MARKERS) {
                if (StringUtils.hasText(environment.getProperty(marker))) {
                    throw new StartupConfigurationException(
                        "A developer or test profile " + Arrays.toString(active) + " is active on a hosted platform ("
                            + marker + " is set). Set SPRING_PROFILES_ACTIVE to the environment's own "
                            + "profile (dev or prod).");
                }
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
