package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E9 #67, decision D6 — one inheritance list, on both auth paths, and a
 * guard that fails the build if either path grows its own again.
 */
class RoleExpansionTest {

    private static final Path JWT_PROVIDER =
        Paths.get("src/main/java/com/example/hms/security/JwtTokenProvider.java");
    private static final Path SECURITY_CONFIG =
        Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java");

    @Test
    @DisplayName("a super-admin holds exactly ROLE_SUPER_ADMIN plus the one inherited list, in order")
    void superAdminInheritsTheOneList() {
        Set<String> expanded = RoleExpansion.expand(List.of("ROLE_SUPER_ADMIN"));

        assertThat(expanded).containsExactly(
            "ROLE_SUPER_ADMIN",
            "ROLE_HOSPITAL_ADMIN", "ROLE_RECEPTIONIST", "ROLE_DOCTOR", "ROLE_NURSE",
            "ROLE_LAB_SCIENTIST", "ROLE_STAFF", "ROLE_PATIENT");
        // The list is the JWT one: what every request already ran under. The
        // seven roles the login path used to add on top are gone from BOTH
        // paths, never added to the request path.
        assertThat(RoleExpansion.SUPER_ADMIN_INHERITS)
            .doesNotContain("ROLE_MIDWIFE", "ROLE_LAB_TECHNICIAN", "ROLE_LAB_MANAGER",
                "ROLE_LAB_DIRECTOR", "ROLE_QUALITY_MANAGER", "ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT");
    }

    @Test
    @DisplayName("a physician or surgeon is a doctor; an ordinary role is left alone")
    void doctorEquivalence() {
        assertThat(RoleExpansion.expand(List.of("ROLE_PHYSICIAN"))).containsExactly("ROLE_PHYSICIAN", "ROLE_DOCTOR");
        assertThat(RoleExpansion.expand(List.of("ROLE_SURGEON"))).containsExactly("ROLE_SURGEON", "ROLE_DOCTOR");
        assertThat(RoleExpansion.expand(List.of("ROLE_NURSE"))).containsExactly("ROLE_NURSE");
    }

    @Test
    @DisplayName("blanks and duplicates are dropped, input order is kept")
    void normalises() {
        assertThat(RoleExpansion.expand(Arrays.asList(" ROLE_NURSE ", null, "", "ROLE_NURSE", "ROLE_MIDWIFE")))
            .containsExactly("ROLE_NURSE", "ROLE_MIDWIFE");
    }

    @Test
    @DisplayName("the password-login mapper is the same rule")
    void mapperIsTheSameRule() {
        List<GrantedAuthority> input = List.of(
            new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"), new SimpleGrantedAuthority("ROLE_SURGEON"));

        assertThat(RoleExpansion.authoritiesMapper().mapAuthorities(input))
            .extracting(GrantedAuthority::getAuthority)
            .containsExactlyElementsOf(RoleExpansion.expand(List.of("ROLE_SUPER_ADMIN", "ROLE_SURGEON")));
    }

    @Test
    @DisplayName("neither auth path carries an inheritance list of its own")
    void bothPathsCallTheOneRule() throws IOException {
        String jwt = Files.readString(JWT_PROVIDER, StandardCharsets.UTF_8);
        String config = Files.readString(SECURITY_CONFIG, StandardCharsets.UTF_8);

        assertThat(jwt).as("JwtTokenProvider expands through RoleExpansion").contains("RoleExpansion.expand(");
        assertThat(config).as("SecurityConfig maps through RoleExpansion").contains("RoleExpansion.authoritiesMapper()");
        // ROLE_STAFF appears in an inheritance list and nowhere else in the
        // JWT provider; in SecurityConfig it belongs only to request matchers.
        assertThat(jwt).as("no inline inheritance list in JwtTokenProvider").doesNotContain("ROLE_STAFF");
        assertThat(config).as("no inline inheritance list in SecurityConfig")
            .doesNotContain("ROLE_STAFF, ROLE_PATIENT")
            .doesNotContain("inherited.forEach");
    }
}
