package com.example.hms.component;

import com.example.hms.enums.ActingMode;
import com.example.hms.security.ActingContext;
import com.example.hms.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.NativeWebRequest;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S5976") // Individual tests preferred over parameterized for clarity
class ActingContextArgumentResolverTest {

    private ActingContextArgumentResolver resolver;

    @Mock
    private MethodParameter parameter;

    @Mock
    private NativeWebRequest webRequest;

    @Mock
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        resolver = new ActingContextArgumentResolver();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═══════════════════════════════════════════════════════════════
    // supportsParameter
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class SupportsParameterTests {

        @Test
        void supportsParameter_returnsTrueForActingContext() {
            when(parameter.getParameterType()).thenReturn((Class) ActingContext.class);

            assertThat(resolver.supportsParameter(parameter)).isTrue();
        }

        @Test
        void supportsParameter_returnsFalseForString() {
            when(parameter.getParameterType()).thenReturn((Class) String.class);

            assertThat(resolver.supportsParameter(parameter)).isFalse();
        }

        @Test
        void supportsParameter_returnsFalseForObject() {
            when(parameter.getParameterType()).thenReturn((Class) Object.class);

            assertThat(resolver.supportsParameter(parameter)).isFalse();
        }

        @Test
        void supportsParameter_returnsFalseForUUID() {
            when(parameter.getParameterType()).thenReturn((Class) UUID.class);

            assertThat(resolver.supportsParameter(parameter)).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // resolveArgument — authentication scenarios
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class ResolveArgument_AuthenticationTests {

        @BeforeEach
        void setUpRequest() {
            when(webRequest.getNativeRequest()).thenReturn(httpRequest);
            // Default: no X-Act-As → STAFF
            lenient().when(httpRequest.getHeader("X-Act-As")).thenReturn(null);
            lenient().when(httpRequest.getHeader("X-Hospital-Id")).thenReturn(null);
            lenient().when(httpRequest.getHeader("X-Role-Code")).thenReturn(null);
        }

        @Test
        void resolveArgument_nullAuthentication_userIdIsNull() {
            // SecurityContext has no authentication set
            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.userId()).isNull();
            assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
        }

        @Test
        void resolveArgument_nonCustomUserDetailsPrincipal_userIdIsNull() {
            // Principal is a plain String, not CustomUserDetails
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("plainStringPrincipal", "pw");
            SecurityContextHolder.getContext().setAuthentication(auth);

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.userId()).isNull();
        }

        @Test
        void resolveArgument_customUserDetailsPrincipal_extractsUserId() {
            UUID userId = UUID.randomUUID();
            CustomUserDetails cud = new CustomUserDetails(userId, "testuser", "pw", true, Collections.emptyList());
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(cud, "pw", Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(auth);

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.userId()).isEqualTo(userId);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // resolveArgument — X-Act-As header scenarios
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class ResolveArgument_ActAsHeaderTests {

        @BeforeEach
        void setUpRequest() {
            when(webRequest.getNativeRequest()).thenReturn(httpRequest);
            lenient().when(httpRequest.getHeader("X-Hospital-Id")).thenReturn(null);
            lenient().when(httpRequest.getHeader("X-Role-Code")).thenReturn(null);
        }

        @Test
        void resolveArgument_actAsNull_defaultsToStaff() {
            when(httpRequest.getHeader("X-Act-As")).thenReturn(null);

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
        }

        @Test
        void resolveArgument_actAsStaff_returnsStaffMode() {
            when(httpRequest.getHeader("X-Act-As")).thenReturn("STAFF");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
        }

        @Test
        void resolveArgument_actAsPatient_returnsPatientMode() {
            when(httpRequest.getHeader("X-Act-As")).thenReturn("PATIENT");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.mode()).isEqualTo(ActingMode.PATIENT);
        }

        @Test
        void resolveArgument_actAsPatientLowerCase_returnsPatientMode() {
            when(httpRequest.getHeader("X-Act-As")).thenReturn("patient");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.mode()).isEqualTo(ActingMode.PATIENT);
        }

        @Test
        void resolveArgument_actAsPatientMixedCase_returnsPatientMode() {
            when(httpRequest.getHeader("X-Act-As")).thenReturn("Patient");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.mode()).isEqualTo(ActingMode.PATIENT);
        }

        @Test
        void resolveArgument_actAsUnknownValue_defaultsToStaff() {
            when(httpRequest.getHeader("X-Act-As")).thenReturn("ADMIN");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // resolveArgument — X-Hospital-Id header scenarios
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class ResolveArgument_HospitalIdHeaderTests {

        @BeforeEach
        void setUpRequest() {
            when(webRequest.getNativeRequest()).thenReturn(httpRequest);
            lenient().when(httpRequest.getHeader("X-Act-As")).thenReturn(null);
            lenient().when(httpRequest.getHeader("X-Role-Code")).thenReturn(null);
        }

        @Test
        void resolveArgument_validHospitalId_parsesUUID() {
            UUID expectedId = UUID.randomUUID();
            when(httpRequest.getHeader("X-Hospital-Id")).thenReturn(expectedId.toString());

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.hospitalId()).isEqualTo(expectedId);
        }

        @Test
        void resolveArgument_nullHospitalId_hospitalIdIsNull() {
            when(httpRequest.getHeader("X-Hospital-Id")).thenReturn(null);

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.hospitalId()).isNull();
        }

        @Test
        void resolveArgument_blankHospitalId_hospitalIdIsNull() {
            when(httpRequest.getHeader("X-Hospital-Id")).thenReturn("   ");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.hospitalId()).isNull();
        }

        @Test
        void resolveArgument_emptyHospitalId_hospitalIdIsNull() {
            when(httpRequest.getHeader("X-Hospital-Id")).thenReturn("");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.hospitalId()).isNull();
        }

        @Test
        void resolveArgument_invalidUUIDHospitalId_hospitalIdIsNull() {
            when(httpRequest.getHeader("X-Hospital-Id")).thenReturn("not-a-uuid");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.hospitalId()).isNull();
        }

        @Test
        void resolveArgument_malformedUUID_hospitalIdIsNull() {
            when(httpRequest.getHeader("X-Hospital-Id")).thenReturn("12345");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.hospitalId()).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // resolveArgument — X-Role-Code header scenarios
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class ResolveArgument_RoleCodeHeaderTests {

        @BeforeEach
        void setUpRequest() {
            when(webRequest.getNativeRequest()).thenReturn(httpRequest);
            lenient().when(httpRequest.getHeader("X-Act-As")).thenReturn(null);
            lenient().when(httpRequest.getHeader("X-Hospital-Id")).thenReturn(null);
        }

        @Test
        void resolveArgument_roleCodePresent_includesInContext() {
            when(httpRequest.getHeader("X-Role-Code")).thenReturn("ROLE_DOCTOR");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.roleCode()).isEqualTo("ROLE_DOCTOR");
        }

        @Test
        void resolveArgument_roleCodeNull_roleCodeIsNull() {
            when(httpRequest.getHeader("X-Role-Code")).thenReturn(null);

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.roleCode()).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // resolveArgument — full integration scenarios
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class ResolveArgument_FullContextTests {

        @BeforeEach
        void setUpRequest() {
            when(webRequest.getNativeRequest()).thenReturn(httpRequest);
        }

        @Test
        void resolveArgument_allHeadersSet_returnsFullyPopulatedContext() {
            UUID userId = UUID.randomUUID();
            UUID hospitalId = UUID.randomUUID();
            CustomUserDetails cud = new CustomUserDetails(userId, "doc", "pw", true, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(cud, "pw", Collections.emptyList()));

            when(httpRequest.getHeader("X-Act-As")).thenReturn("PATIENT");
            when(httpRequest.getHeader("X-Hospital-Id")).thenReturn(hospitalId.toString());
            when(httpRequest.getHeader("X-Role-Code")).thenReturn("ROLE_NURSE");

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx)
                .satisfies(c -> {
                    assertThat(c.userId()).isEqualTo(userId);
                    assertThat(c.hospitalId()).isEqualTo(hospitalId);
                    assertThat(c.mode()).isEqualTo(ActingMode.PATIENT);
                    assertThat(c.roleCode()).isEqualTo("ROLE_NURSE");
                });
        }

        @Test
        void resolveArgument_noHeadersNoAuth_returnsMinimalContext() {
            when(httpRequest.getHeader("X-Act-As")).thenReturn(null);
            when(httpRequest.getHeader("X-Hospital-Id")).thenReturn(null);
            when(httpRequest.getHeader("X-Role-Code")).thenReturn(null);

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx)
                .satisfies(c -> {
                    assertThat(c.userId()).isNull();
                    assertThat(c.hospitalId()).isNull();
                    assertThat(c.mode()).isEqualTo(ActingMode.STAFF);
                    assertThat(c.roleCode()).isNull();
                });
        }

        @Test
        void resolveArgument_staffModeWithInvalidHospitalId_hospitalIdNull() {
            when(httpRequest.getHeader("X-Act-As")).thenReturn("STAFF");
            when(httpRequest.getHeader("X-Hospital-Id")).thenReturn("garbage-value");
            when(httpRequest.getHeader("X-Role-Code")).thenReturn(null);

            ActingContext ctx = (ActingContext) resolver.resolveArgument(parameter, null, webRequest, null);

            assertThat(ctx.hospitalId()).isNull();
            assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
        }
    }
}
