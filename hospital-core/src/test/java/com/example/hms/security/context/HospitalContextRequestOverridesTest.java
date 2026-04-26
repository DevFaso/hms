package com.example.hms.security.context;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
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
    }

    @Test
    void outOfScopeHeaderIsIgnored() {
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .permittedHospitalIds(Set.of(hospitalA, hospitalB))
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader(hospitalC.toString()));

        assertThat(result.getActiveHospitalId())
            .as("out-of-scope hospital must not become active")
            .isEqualTo(hospitalA);
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
    void emptyPermittedScopeAllowsOverride() {
        // A user with no explicit permitted scope (e.g. global staff) is
        // not blocked from picking an active hospital — matches the
        // legacy filter's behaviour.
        HospitalContext context = HospitalContext.builder().build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader(hospitalA.toString()));

        assertThat(result.getActiveHospitalId()).isEqualTo(hospitalA);
    }

    @Test
    void malformedUuidIsIgnored() {
        HospitalContext context = HospitalContext.builder()
            .activeHospitalId(hospitalA)
            .permittedHospitalIds(Set.of(hospitalA))
            .build();

        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(context, requestWithHeader("not-a-uuid"));

        assertThat(result.getActiveHospitalId()).isEqualTo(hospitalA);
    }

    @Test
    void nullContextYieldsEmptyContextUnchanged() {
        HospitalContext result = HospitalContextRequestOverrides
            .applyRequestOverrides(null, requestWithHeader(hospitalA.toString()));

        // Empty context has empty permitted scope → the override IS allowed
        // (matches the existing rule). Just verify we don't NPE on null in.
        assertThat(result).isNotNull();
        assertThat(result.getActiveHospitalId()).isEqualTo(hospitalA);
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
