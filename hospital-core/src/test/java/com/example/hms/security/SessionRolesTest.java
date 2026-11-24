package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A continued session keeps the roles it was opened with, less those removed since. */
class SessionRolesTest {

    @Test
    @DisplayName("a receptionist session of a receptionist+doctor account stays receptionist")
    void neverAddsARoleTheSessionDidNotCarry() {
        assertThat(SessionRoles.stillHeld(List.of("ROLE_RECEPTIONIST"),
                List.of("ROLE_RECEPTIONIST", "ROLE_DOCTOR", "ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN")))
            .containsExactly("ROLE_RECEPTIONIST");
    }

    @Test
    @DisplayName("a role removed since sign-in drops out; none left is empty")
    void dropsARoleNoLongerHeld() {
        assertThat(SessionRoles.stillHeld(List.of("ROLE_NURSE", "ROLE_DOCTOR"), List.of("ROLE_NURSE")))
            .containsExactly("ROLE_NURSE");
        assertThat(SessionRoles.stillHeld(List.of("ROLE_DOCTOR"), List.of("ROLE_NURSE"))).isEmpty();
    }

    @Test
    @DisplayName("nothing carried, or nothing held, is empty: never a fallback to what is held")
    void emptyInputsAreEmpty() {
        assertThat(SessionRoles.stillHeld(List.of(), List.of("ROLE_DOCTOR"))).isEmpty();
        assertThat(SessionRoles.stillHeld(null, List.of("ROLE_DOCTOR"))).isEmpty();
        assertThat(SessionRoles.stillHeld(List.of("ROLE_DOCTOR"), List.of())).isEmpty();
        assertThat(SessionRoles.stillHeld(List.of("ROLE_DOCTOR"), null)).isEmpty();
    }

    @Test
    @DisplayName("a role picked from the sign-in widening still counts as held, in the held spelling")
    void widenedPicksStillCountAndUseTheHeldSpelling() {
        // A surgeon picked DOCTOR (doctor equivalence) at sign-in.
        assertThat(SessionRoles.stillHeld(List.of("ROLE_DOCTOR"), List.of("ROLE_SURGEON")))
            .containsExactly("ROLE_DOCTOR");
        assertThat(SessionRoles.stillHeld(List.of("role_nurse"), List.of("ROLE_NURSE")))
            .containsExactly("ROLE_NURSE");
    }

    @Test
    @DisplayName("a super-admin session loses its inherited roles with the super-admin role")
    void aDemotedSuperAdminKeepsNothingInherited() {
        assertThat(SessionRoles.stillHeld(List.of("ROLE_SUPER_ADMIN"), List.of("ROLE_NURSE"))).isEmpty();
        assertThat(SessionRoles.stillHeld(List.of("ROLE_HOSPITAL_ADMIN"), List.of("ROLE_NURSE"))).isEmpty();
    }
}
