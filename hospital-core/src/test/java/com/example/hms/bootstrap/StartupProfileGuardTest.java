package com.example.hms.bootstrap;

import com.example.hms.seed.OrganizationSecuritySeeder;
import com.example.hms.seed.RoleSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The application starts only on a deliberate profile set")
class StartupProfileGuardTest {

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    @DisplayName("no active profile: refused, there is no default")
    void noProfileIsRefused() {
        assertThatThrownBy(() -> StartupProfileGuard.check(environment()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SPRING_PROFILES_ACTIVE");
    }

    @Test
    @DisplayName("each profile of its own family starts")
    void eachDeliberateProfileStarts() {
        for (String profile : List.of("dev", "prod", "test", "local", "local-h2")) {
            assertThatCode(() -> StartupProfileGuard.check(environment(profile)))
                .as(profile).doesNotThrowAnyException();
        }
        assertThatCode(() -> StartupProfileGuard.check(environment("prod", "test")))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a developer profile next to a deployed one is refused")
    void aDeveloperProfileNeverJoinsADeployedOne() {
        for (String deployed : DeploymentProfiles.DEPLOYED) {
            for (String developer : DeploymentProfiles.DEVELOPER) {
                assertThatThrownBy(() -> StartupProfileGuard.check(environment(deployed, developer)))
                    .as(deployed + "+" + developer)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("developer profile");
            }
        }
    }

    @Test
    @DisplayName("a developer or test profile on a hosted platform is refused; a deployed one is not")
    void aDeveloperProfileNeverRunsOnAHostedPlatform() {
        for (String marker : StartupProfileGuard.HOSTED_PLATFORM_MARKERS) {
            for (String committed : List.of("local-h2", "local", "test")) {
                MockEnvironment hosted = environment(committed);
                hosted.setProperty(marker, "some-value");
                assertThatThrownBy(() -> StartupProfileGuard.check(hosted))
                    .as(committed + " with " + marker)
                    .isInstanceOf(StartupConfigurationException.class)
                    .hasMessageContaining(marker);
            }
            MockEnvironment testAndProd = environment("prod", "test");
            testAndProd.setProperty(marker, "some-value");
            assertThatThrownBy(() -> StartupProfileGuard.check(testAndProd))
                .isInstanceOf(StartupConfigurationException.class);

            MockEnvironment deployed = environment("dev");
            deployed.setProperty(marker, "some-value");
            assertThatCode(() -> StartupProfileGuard.check(deployed)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the guard is registered, so every start runs it")
    void theGuardIsRegistered() throws Exception {
        Properties factories = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/META-INF/spring.factories"))) {
            factories.load(in);
        }
        assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
            .contains(StartupProfileGuard.class.getName());
    }

    @Test
    @DisplayName("every seeder runs on a developer profile only, never next to a deployed one")
    void seedersRunOnlyOnDeveloperProfiles() {
        for (Class<?> seeder : List.of(DevSyntheticDataSeeder.class, RoleSeeder.class, OrganizationSecuritySeeder.class)) {
            Profile profile = seeder.getAnnotation(Profile.class);
            assertThat(profile).as(seeder.getSimpleName()).isNotNull();
            assertThat(profile.value()).as(seeder.getSimpleName()).containsExactly(DeploymentProfiles.SEEDING);
        }
        Profiles seeding = Profiles.of(DeploymentProfiles.SEEDING);
        assertThat(seeding.matches(Set.of("local-h2")::contains)).isTrue();
        assertThat(seeding.matches(Set.of("local")::contains)).isTrue();
        for (String deployed : DeploymentProfiles.DEPLOYED) {
            assertThat(seeding.matches(Set.of(deployed)::contains)).as(deployed).isFalse();
            assertThat(seeding.matches(Set.of(deployed, "local-h2")::contains)).as(deployed + "+local-h2").isFalse();
            assertThat(seeding.matches(Set.of(deployed, "local")::contains)).as(deployed + "+local").isFalse();
        }
        assertThat(seeding.matches(Set.of("test")::contains)).isFalse();
        assertThat(seeding.matches(Set.<String>of()::contains)).isFalse();
    }

    @Test
    @DisplayName("the seeding expression names every deployed profile")
    void theSeedingExpressionNamesEveryDeployedProfile() {
        for (String deployed : DeploymentProfiles.DEPLOYED) {
            assertThat(DeploymentProfiles.SEEDING).contains(deployed);
        }
    }

    @Test
    @DisplayName("H2 is never packaged: it is a test and development-only dependency")
    void h2IsNotARuntimeDependency() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build).contains("testAndDevelopmentOnly 'com.h2database:h2'");
        assertThat(build).doesNotContainPattern("(?m)^\\s*(runtimeOnly|implementation)\\s+'com\\.h2database:h2'");
    }
}
