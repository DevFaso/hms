package com.example.hms.service.lab;

import com.example.hms.exception.UnauthorizedAccessException;
import com.example.hms.model.LabTestDefinition;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may record a lab result.
 *
 * <p>The rule under test is not "which role" but "which role for which test".
 * A nurse recording a bedside glucose reading is doing their job; the same
 * nurse typing in a chemistry panel they never ran is the problem this closes.
 */
@DisplayName("LabResultEntryGuard")
class LabResultEntryGuardTest {

    private final LabResultEntryGuard guard = new LabResultEntryGuard();

    /** Put the guard in the state a hospital reaches once it marks its catalogue. */
    private void enforcePointOfCare(boolean on) {
        org.springframework.test.util.ReflectionTestUtils.setField(
            guard, "pointOfCareEnforcementEnabled", on);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("user", "n/a",
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private LabTestDefinition test(boolean pointOfCare) {
        LabTestDefinition t = new LabTestDefinition();
        t.setName("Glucose");
        t.setPointOfCare(pointOfCare);
        return t;
    }

    @Test
    @DisplayName("a nurse may record a point-of-care test")
    void nurseMayRecordPointOfCare() {
        // The reading exists whether or not the software accepts it. Blocking
        // this does not make the record safer, it makes the glucose go
        // unrecorded.
        authenticateAs("ROLE_NURSE");
        assertThatCode(() -> guard.requireMayEnterResult(test(true)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a nurse may NOT record a laboratory-performed test")
    void nurseMayNotRecordLaboratoryTest() {
        // THE REGRESSION. A nurse could create, edit and release a result for
        // a panel the laboratory ran and the nurse had no part in.
        enforcePointOfCare(true);
        authenticateAs("ROLE_NURSE");
        assertThatThrownBy(() -> guard.requireMayEnterResult(test(false)))
            .isInstanceOf(UnauthorizedAccessException.class)
            .hasMessageContaining("Only laboratory staff");
    }

    @Test
    @DisplayName("the refusal tells a bedside role how to fix it")
    void refusalNamesTheRemedy() {
        // Usually the answer is "an administrator marks this test", not "your
        // permissions are wrong". A ward that cannot tell those apart concludes
        // the system is broken and works around it.
        enforcePointOfCare(true);
        authenticateAs("ROLE_MIDWIFE");
        assertThatThrownBy(() -> guard.requireMayEnterResult(test(false)))
            .hasMessageContaining("point-of-care")
            .hasMessageContaining("Glucose");
    }

    @Test
    @DisplayName("a doctor is a bedside role here, not a laboratory one")
    void doctorIsBedsideNotLaboratory() {
        // A doctor running a bedside glucose is point-of-care work. A doctor
        // typing in a chemistry panel is the same problem as a nurse doing it,
        // so DOCTOR is deliberately not in LABORATORY_ROLES.
        enforcePointOfCare(true);
        authenticateAs("ROLE_DOCTOR");
        assertThatCode(() -> guard.requireMayEnterResult(test(true)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireMayEnterResult(test(false)))
            .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    @DisplayName("laboratory staff may record any test")
    void laboratoryMayRecordAnything() {
        for (String role : LabResultAuthority.LABORATORY_ROLES) {
            SecurityContextHolder.clearContext();
            authenticateAs(role);
            assertThatCode(() -> guard.requireMayEnterResult(test(false)))
                .as("%s must be able to record a laboratory test", role)
                .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a result with no test at all is laboratory-only")
    void unknownTestIsLaboratoryOnly() {
        // A result that cannot name its test is not something a bedside device
        // produced, so it fails closed rather than open.
        enforcePointOfCare(true);
        authenticateAs("ROLE_NURSE");
        assertThatThrownBy(() -> guard.requireMayEnterResult(null))
            .isInstanceOf(UnauthorizedAccessException.class);

        SecurityContextHolder.clearContext();
        authenticateAs("ROLE_LAB_SCIENTIST");
        assertThatCode(() -> guard.requireMayEnterResult(null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a role with no lab authority at all is refused outright")
    void unrelatedRoleIsRefused() {
        authenticateAs("ROLE_RECEPTIONIST");
        assertThatThrownBy(() -> guard.requireMayEnterResult(test(true)))
            .isInstanceOf(UnauthorizedAccessException.class)
            .hasMessageContaining("may not enter laboratory results");
    }

    @Test
    @DisplayName("by default a ward is NOT blocked — enforcement is opt-in")
    void defaultDoesNotBlockTheWard() {
        // In a real ward a nurse takes a glucometer reading and charts it.
        // Nobody waits for an administrator to tick a box first. Shipping the
        // restriction on would have blocked every ward from recording anything
        // until somebody marked a catalogue, which is a day-one capability
        // regression for no clinical gain.
        //
        // This is only safe because the DANGEROUS half — a nurse releasing a
        // laboratory result they never produced — is closed unconditionally by
        // RELEASE_ROLES, not by this flag. See releaseIsTheLaboratorysCall.
        authenticateAs("ROLE_NURSE");
        assertThatCode(() -> guard.requireMayEnterResult(test(false)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforcement never widens who may enter — it only narrows")
    void enforcementOnlyNarrows() {
        // Whichever way the flag is set, a role with no lab authority is out
        // and laboratory staff are in. The flag moves exactly one boundary.
        for (boolean on : new boolean[] {false, true}) {
            enforcePointOfCare(on);

            SecurityContextHolder.clearContext();
            authenticateAs("ROLE_RECEPTIONIST");
            assertThatThrownBy(() -> guard.requireMayEnterResult(test(true)))
                .as("receptionist must never enter results (enforcement=%s)", on)
                .isInstanceOf(UnauthorizedAccessException.class);

            SecurityContextHolder.clearContext();
            authenticateAs("ROLE_LAB_SCIENTIST");
            assertThatCode(() -> guard.requireMayEnterResult(test(false)))
                .as("lab staff must always enter results (enforcement=%s)", on)
                .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("release excludes the roles that order tests")
    void releaseIsTheLaboratorysCall() {
        // Release is the laboratory attesting a number may be acted on, and it
        // mirrors microbiology's FINALIZE set exactly. The person who ordered
        // the test is not the person who should attest to its result — which
        // is why release used to be WIDER than sign, the lesser act.
        assertThat(LabResultAuthority.RELEASE_ROLES)
            .doesNotContain("ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_DOCTOR")
            .containsExactlyInAnyOrder(
                "ROLE_LAB_SCIENTIST", "ROLE_LAB_MANAGER", "ROLE_LAB_DIRECTOR", "ROLE_SUPER_ADMIN");
    }
}
