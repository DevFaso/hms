package com.example.hms.bootstrap;

import java.util.Arrays;
import java.util.Set;

/**
 * The Spring profiles the application knows, split by where they may run.
 *
 * <p>A <em>developer</em> profile ({@code local}, {@code local-h2}) carries committed credentials
 * and seeds data; the {@code test} profile carries committed test credentials. A <em>deployed</em>
 * profile ({@code dev}, {@code prod}, …) must bring its own secrets. The two families never mix:
 * {@link StartupProfileGuard} refuses the combination, and the seeders' {@link #SEEDING} expression
 * excludes every deployed profile on its own as well.
 */
public final class DeploymentProfiles {

    public static final String LOCAL = "local";
    public static final String LOCAL_H2 = "local-h2";
    public static final String TEST = "test";

    /** Profiles of a developer machine: committed credentials, seeded data. */
    public static final Set<String> DEVELOPER = Set.of(LOCAL, LOCAL_H2);

    /** Profiles of a shared, deployed environment, which must never seed or use committed secrets. */
    public static final Set<String> DEPLOYED = Set.of("dev", "prod", "production", "uat", "staging");

    /**
     * The {@code @Profile} expression of every data seeder: a developer profile, and no deployed
     * one alongside it.
     */
    public static final String SEEDING = "(local | local-h2) & !(dev | prod | production | uat | staging)";

    private DeploymentProfiles() {
    }

    /**
     * Whether every active profile is a developer or test profile. False when none is active:
     * an environment that names no profile is treated as deployed.
     */
    public static boolean onlyDeveloperOrTest(String[] activeProfiles) {
        return activeProfiles != null && activeProfiles.length > 0
            && Arrays.stream(activeProfiles).allMatch(p -> DEVELOPER.contains(p) || TEST.equals(p));
    }

    /** Whether any active profile is a developer profile. */
    public static boolean anyDeveloper(String[] activeProfiles) {
        return activeProfiles != null && Arrays.stream(activeProfiles).anyMatch(DEVELOPER::contains);
    }

    /** Whether any active profile is a deployed profile. */
    public static boolean anyDeployed(String[] activeProfiles) {
        return activeProfiles != null && Arrays.stream(activeProfiles).anyMatch(DEPLOYED::contains);
    }
}
