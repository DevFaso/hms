package com.example.hms.security.context;

import com.example.hms.security.tenant.ActingScope;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;
import java.util.UUID;

import static com.example.hms.security.context.HospitalContextRequestOverrides.HEADER_HOSPITAL_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit coverage for the shared {@link HospitalContextRequestOverrides}
 * helper. Same rules apply to the legacy {@code JwtAuthenticationFilter}
 * path and the OIDC {@code KeycloakHospitalContextFilter} path — drift
 * between the two would silently break multi-hospital users at cutover.
 *
 * <p>Design Q3, option A: a header naming a hospital the caller may not use
 * (outside the permitted set, an empty set, a malformed value) is REFUSED —
 * the context carries {@code NOT_PERMITTED} and no acting hospital, and the
 * filters answer 403 — instead of being ignored while the request runs at the
 * default hospital. Four cases changed with it and are renamed accordingly.
 */
class HospitalContextRequestOverridesTest {

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID hospitalC = UUID.randomUUID();

    @Test
    void noHeaderLeavesContextUnchanged() {
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .permittedHospitalIds(Set.of(hospitalA, hospitalB))
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, new MockHttpServletRequest());

        assertThat(result.getActiveHospitalId()).isEqualTo(hospitalA);
        assertThat(result.getPermittedHospitalIds()).containsExactlyInAnyOrder(hospitalA, hospitalB);
    }

    @Test
    void blankHeaderLeavesContextUnchanged() {
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .permittedHospitalIds(Set.of(hospitalA, hospitalB))
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader("   "));

        assertThat(result.getActiveHospitalId()).isEqualTo(hospitalA);
    }

    @Test
    void inScopeHeaderSwitchesActiveHospital() {
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .permittedHospitalIds(Set.of(hospitalA, hospitalB))
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader(hospitalB.toString()));

        assertThat(result.getActiveHospitalId()).isEqualTo(hospitalB);
        assertThat(result.getPermittedHospitalIds())
            .as("permitted scope is unchanged by the override")
            .containsExactlyInAnyOrder(hospitalA, hospitalB);
        assertThat(result.isHeaderOverridden())
            .as("a successful X-Hospital-Id override must flip the marker so "
                + "RoleValidator can distinguish it from the JWT-derived primary")
            .isTrue();
    }

    @Test
    void noHeaderLeavesHeaderOverriddenFalse() {
        // Companion to the test above: when no X-Hospital-Id header is
        // present, the context comes back unchanged AND headerOverridden
        // stays false. RoleValidator depends on this distinction to drop
        // the JWT-derived primary hospital for super-admins.
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .permittedHospitalIds(Set.of(hospitalA, hospitalB))
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, new MockHttpServletRequest());

        assertThat(result.getActiveHospitalId()).isEqualTo(hospitalA);
        assertThat(result.isHeaderOverridden()).isFalse();
    }

    @Test
    void outOfScopeHeaderIsRefused() {
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .permittedHospitalIds(Set.of(hospitalA, hospitalB))
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader(hospitalC.toString()));

        assertThat(result.getScopeRefusal()).isEqualTo(ActingScope.Reason.NOT_PERMITTED);
        assertThat(result.getRefusedHospitalId()).isEqualTo(hospitalC);
        assertThat(result.getActiveHospitalId())
            .as("neither the named hospital nor the default one: the request acts nowhere")
            .isNull();
        assertThat(result.pinnedHospitalId()).isNull();
    }

    @Test
    void superAdminCanOverrideToAnyHospital() {
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .permittedHospitalIds(Set.of(hospitalA))
            .superAdmin(true)
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader(hospitalC.toString()));

        assertThat(result.getActiveHospitalId()).isEqualTo(hospitalC);
    }

    @Test
    void emptyPermittedScopeRefusesOverride() {
        // An empty permitted set means the principal holds no hospital —
        // a patient's global ROLE_PATIENT assignment, a user revoked after
        // sign-in (legacy HMS-token path, which reads the set live), a
        // Keycloak token with no hospital claims. It used to be
        // read as "may pick any", which let each of them act at whatever
        // hospital the header named.
        HospitalContext context = HospitalContext.builder().build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader(hospitalA.toString()));

        assertThat(result.getActiveHospitalId())
            .as("no permitted hospital, so no hospital the header may make active")
            .isNull();
        assertThat(result.isHeaderOverridden()).isFalse();
        assertThat(result.pinnedHospitalId()).isNull();
        assertThat(result.getScopeRefusal()).isEqualTo(ActingScope.Reason.NOT_PERMITTED);
    }

    @Test
    void emptyPermittedScopeRefusesTheHeaderAndActsNowhere() {
        // A context built by hand with an acting hospital but no permitted set
        // (the defensive shape; neither producer builds it): the header cannot
        // replace the hospital, and the request does not fall back to it either.
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader(hospitalB.toString()));

        assertThat(result.getScopeRefusal()).isEqualTo(ActingScope.Reason.NOT_PERMITTED);
        assertThat(result.getActiveHospitalId()).isNull();
        assertThat(result.isHeaderOverridden()).isFalse();
    }

    @Test
    void superAdminWithEmptyPermittedScopeCanStillOverride() {
        // A platform super-admin often holds no hospital assignment at all;
        // the chip-scoped view must keep working for them.
        HospitalContext context = HospitalContext.builder()
            .superAdmin(true)
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader(hospitalC.toString()));

        assertThat(result.getActiveHospitalId()).isEqualTo(hospitalC);
        assertThat(result.isHeaderOverridden()).isTrue();
    }

    @Test
    void malformedUuidIsRefused() {
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .permittedHospitalIds(Set.of(hospitalA))
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader("not-a-uuid"));

        assertThat(result.getScopeRefusal()).isEqualTo(ActingScope.Reason.NOT_PERMITTED);
        assertThat(result.getRefusedHospitalId()).as("nothing to name").isNull();
        assertThat(result.getActiveHospitalId()).isNull();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void malformedHeaderValueIsNotLogged(CapturedOutput output) {
        // Caller-controlled: a CR/LF in it would forge a log line.
        String forged = "x\r\n2026-01-01 INFO [AUTH] FORGED-LINE-MARKER";
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .permittedHospitalIds(Set.of(hospitalA))
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader(forged));

        assertThat(result.getScopeRefusal()).isEqualTo(ActingScope.Reason.NOT_PERMITTED);
        assertThat(output.getAll())
            .as("the WARN is written")
            .contains("Refusing malformed X-Hospital-Id header")
            .as("but never the caller's value")
            .doesNotContain("FORGED-LINE-MARKER");
    }

    @Test
    void nullContextYieldsEmptyContextUnchanged() {
        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(null, requestWithHeader(hospitalA.toString()));

        // A null context is treated as the empty one: no permitted hospital,
        // so the header is refused. Also verifies we don't NPE on null in.
        assertThat(result).isNotNull();
        assertThat(result.getActiveHospitalId()).isNull();
        assertThat(result.isHeaderOverridden()).isFalse();
        assertThat(result.getScopeRefusal()).isEqualTo(ActingScope.Reason.NOT_PERMITTED);
    }

    @Test
    void nullRequestReturnsContextUnchanged() {
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, null);

        assertThat(result.getActiveHospitalId()).isEqualTo(hospitalA);
    }

    private HttpServletRequest requestWithHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER_HOSPITAL_ID, value);
        return request;
    }
}
