package com.example.hms.component;

import com.example.hms.enums.ActingMode;
import com.example.hms.security.ActingContext;
import com.example.hms.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActingContextInterceptor")
@SuppressWarnings("java:S5976") // Individual tests preferred over parameterized for clarity
class ActingContextInterceptorTest {

    private ActingContextInterceptor interceptor;

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    private final Object handler = new Object();

    @BeforeEach
    void setUp() {
        interceptor = new ActingContextInterceptor();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private void setAuthentication(Authentication auth) {
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private CustomUserDetails userDetails(UUID userId) {
        return new CustomUserDetails(
                userId, "user@test.com", "password", true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private ActingContext captureActingContext() {
        ArgumentCaptor<ActingContext> captor = ArgumentCaptor.forClass(ActingContext.class);
        verify(request).setAttribute(eq("ACTING_CONTEXT"), captor.capture());
        return captor.getValue();
    }

    // ─── Auth Null ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("when authentication is null")
    class AuthNullTests {

        @Test
        @DisplayName("returns true immediately")
        void preHandle_authNull_returnsTrue() {
            // No authentication set → SecurityContextHolder returns null auth
            boolean result = interceptor.preHandle(request, response, handler);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("does not set request attribute")
        void preHandle_authNull_doesNotSetAttribute() {
            interceptor.preHandle(request, response, handler);

            verify(request, never()).setAttribute(anyString(), any());
        }

        @Test
        @DisplayName("does not read any headers")
        void preHandle_authNull_doesNotReadHeaders() {
            interceptor.preHandle(request, response, handler);

            verify(request, never()).getHeader(anyString());
        }
    }

    // ─── Auth Present ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("when authentication is present")
    class AuthPresentTests {

        private UUID userId;

        @BeforeEach
        void setUp() {
            userId = UUID.randomUUID();
            CustomUserDetails details = userDetails(userId);
            setAuthentication(
                    new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        }

        @Test
        @DisplayName("always returns true")
        void preHandle_authPresent_returnsTrue() {
            when(request.getHeader("X-Act-As")).thenReturn("STAFF");

            boolean result = interceptor.preHandle(request, response, handler);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("extracts userId from CustomUserDetails principal")
        void preHandle_extractsUserId() {
            when(request.getHeader("X-Act-As")).thenReturn("STAFF");

            interceptor.preHandle(request, response, handler);

            ActingContext ctx = captureActingContext();
            assertThat(ctx.userId()).isEqualTo(userId);
        }

        // ─── X-Act-As Header ────────────────────────────────────────────────

        @Nested
        @DisplayName("X-Act-As header handling")
        class ActAsHeaderTests {

            @Test
            @DisplayName("null X-Act-As defaults to STAFF mode")
            void actAs_null_defaultsToStaff() {
                when(request.getHeader("X-Act-As")).thenReturn(null);

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
            }

            @Test
            @DisplayName("X-Act-As 'STAFF' yields STAFF mode")
            void actAs_staff_uppercase() {
                when(request.getHeader("X-Act-As")).thenReturn("STAFF");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
            }

            @Test
            @DisplayName("X-Act-As 'staff' (lowercase) yields STAFF mode")
            void actAs_staff_lowercase() {
                when(request.getHeader("X-Act-As")).thenReturn("staff");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
            }

            @Test
            @DisplayName("X-Act-As 'PATIENT' yields PATIENT mode")
            void actAs_patient_uppercase() {
                when(request.getHeader("X-Act-As")).thenReturn("PATIENT");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.mode()).isEqualTo(ActingMode.PATIENT);
            }

            @Test
            @DisplayName("X-Act-As 'patient' (lowercase) yields PATIENT mode")
            void actAs_patient_lowercase() {
                when(request.getHeader("X-Act-As")).thenReturn("patient");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.mode()).isEqualTo(ActingMode.PATIENT);
            }

            @Test
            @DisplayName("X-Act-As 'Patient' (mixed case) yields PATIENT mode")
            void actAs_patient_mixedCase() {
                when(request.getHeader("X-Act-As")).thenReturn("Patient");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.mode()).isEqualTo(ActingMode.PATIENT);
            }

            @Test
            @DisplayName("X-Act-As unrecognized value defaults to STAFF mode")
            void actAs_unknown_defaultsToStaff() {
                when(request.getHeader("X-Act-As")).thenReturn("ADMIN");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
            }

            @Test
            @DisplayName("X-Act-As empty string defaults to STAFF mode")
            void actAs_empty_defaultsToStaff() {
                when(request.getHeader("X-Act-As")).thenReturn("");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
            }
        }

        // ─── X-Hospital-Id Header – STAFF mode ─────────────────────────────

        @Nested
        @DisplayName("X-Hospital-Id header in STAFF mode")
        class HospitalIdStaffModeTests {

            @BeforeEach
            void setUp() {
                // Ensure STAFF mode
                when(request.getHeader("X-Act-As")).thenReturn("STAFF");
            }

            @Test
            @DisplayName("valid UUID is parsed correctly")
            void hospitalId_validUuid() {
                UUID hospitalId = UUID.randomUUID();
                when(request.getHeader("X-Hospital-Id")).thenReturn(hospitalId.toString());

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.hospitalId()).isEqualTo(hospitalId);
            }

            @Test
            @DisplayName("null X-Hospital-Id yields null hospitalId")
            void hospitalId_null() {
                when(request.getHeader("X-Hospital-Id")).thenReturn(null);

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.hospitalId()).isNull();
            }

            @Test
            @DisplayName("empty X-Hospital-Id yields null hospitalId")
            void hospitalId_empty() {
                when(request.getHeader("X-Hospital-Id")).thenReturn("");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.hospitalId()).isNull();
            }

            @Test
            @DisplayName("blank X-Hospital-Id yields null hospitalId")
            void hospitalId_blank() {
                when(request.getHeader("X-Hospital-Id")).thenReturn("   ");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.hospitalId()).isNull();
            }

            @Test
            @DisplayName("invalid UUID throws IllegalArgumentException")
            void hospitalId_invalidUuid() {
                when(request.getHeader("X-Hospital-Id")).thenReturn("not-a-uuid");

                assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        // ─── X-Hospital-Id Header – PATIENT mode ───────────────────────────

        @Nested
        @DisplayName("X-Hospital-Id header in PATIENT mode")
        class HospitalIdPatientModeTests {

            @BeforeEach
            void setUp() {
                when(request.getHeader("X-Act-As")).thenReturn("PATIENT");
            }

            @Test
            @DisplayName("hospitalId is always null in PATIENT mode, even when header present")
            void hospitalId_patientMode_alwaysNull() {
                // The interceptor only reads X-Hospital-Id when mode == STAFF
                // So even if a header is present, it's never read in PATIENT mode
                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.hospitalId()).isNull();
            }

            @Test
            @DisplayName("X-Hospital-Id header is not read in PATIENT mode")
            void hospitalId_patientMode_headerNotRead() {
                interceptor.preHandle(request, response, handler);

                verify(request, never()).getHeader("X-Hospital-Id");
            }
        }

        // ─── X-Role-Code Header ─────────────────────────────────────────────

        @Nested
        @DisplayName("X-Role-Code header handling")
        class RoleCodeTests {

            @BeforeEach
            void setUp() {
                lenient().when(request.getHeader("X-Act-As")).thenReturn("STAFF");
                lenient().when(request.getHeader("X-Hospital-Id")).thenReturn(null);
            }

            @Test
            @DisplayName("role code is captured when present")
            void roleCode_present() {
                when(request.getHeader("X-Role-Code")).thenReturn("ADMIN");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.roleCode()).isEqualTo("ADMIN");
            }

            @Test
            @DisplayName("role code is null when header absent")
            void roleCode_null() {
                when(request.getHeader("X-Role-Code")).thenReturn(null);

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.roleCode()).isNull();
            }

            @Test
            @DisplayName("empty role code is captured as-is")
            void roleCode_empty() {
                when(request.getHeader("X-Role-Code")).thenReturn("");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.roleCode()).isEmpty();
            }
        }

        // ─── Request Attribute ──────────────────────────────────────────────

        @Nested
        @DisplayName("request attribute 'ACTING_CONTEXT'")
        class RequestAttributeTests {

            @Test
            @DisplayName("sets ACTING_CONTEXT attribute with correct key")
            void setsAttributeWithCorrectKey() {
                when(request.getHeader("X-Act-As")).thenReturn("STAFF");

                interceptor.preHandle(request, response, handler);

                verify(request).setAttribute(eq("ACTING_CONTEXT"), any(ActingContext.class));
            }

            @Test
            @DisplayName("attribute contains fully-populated ActingContext")
            void attributeContainsFullContext() {
                UUID hospitalId = UUID.randomUUID();
                when(request.getHeader("X-Act-As")).thenReturn("STAFF");
                when(request.getHeader("X-Hospital-Id")).thenReturn(hospitalId.toString());
                when(request.getHeader("X-Role-Code")).thenReturn("DOCTOR");

                interceptor.preHandle(request, response, handler);

                ActingContext ctx = captureActingContext();
                assertThat(ctx.userId()).isEqualTo(userId);
                assertThat(ctx.hospitalId()).isEqualTo(hospitalId);
                assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
                assertThat(ctx.roleCode()).isEqualTo("DOCTOR");
            }
        }

        // ─── Full Integration Scenarios ─────────────────────────────────────

        @Nested
        @DisplayName("full scenario integration")
        class FullScenarioTests {

            @Test
            @DisplayName("STAFF mode with all headers populated")
            void staffMode_allHeaders() {
                UUID hospitalId = UUID.randomUUID();
                when(request.getHeader("X-Act-As")).thenReturn("STAFF");
                when(request.getHeader("X-Hospital-Id")).thenReturn(hospitalId.toString());
                when(request.getHeader("X-Role-Code")).thenReturn("NURSE");

                boolean result = interceptor.preHandle(request, response, handler);

                assertThat(result).isTrue();
                ActingContext ctx = captureActingContext();
                assertThat(ctx.userId()).isEqualTo(userId);
                assertThat(ctx.hospitalId()).isEqualTo(hospitalId);
                assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
                assertThat(ctx.roleCode()).isEqualTo("NURSE");
            }

            @Test
            @DisplayName("PATIENT mode with minimal headers")
            void patientMode_minimal() {
                when(request.getHeader("X-Act-As")).thenReturn("PATIENT");

                boolean result = interceptor.preHandle(request, response, handler);

                assertThat(result).isTrue();
                ActingContext ctx = captureActingContext();
                assertThat(ctx.userId()).isEqualTo(userId);
                assertThat(ctx.hospitalId()).isNull();
                assertThat(ctx.mode()).isEqualTo(ActingMode.PATIENT);
                assertThat(ctx.roleCode()).isNull();
            }

            @Test
            @DisplayName("STAFF mode with no optional headers")
            void staffMode_noOptionalHeaders() {
                when(request.getHeader("X-Act-As")).thenReturn(null);
                when(request.getHeader("X-Hospital-Id")).thenReturn(null);
                when(request.getHeader("X-Role-Code")).thenReturn(null);

                boolean result = interceptor.preHandle(request, response, handler);

                assertThat(result).isTrue();
                ActingContext ctx = captureActingContext();
                assertThat(ctx.userId()).isEqualTo(userId);
                assertThat(ctx.hospitalId()).isNull();
                assertThat(ctx.mode()).isEqualTo(ActingMode.STAFF);
                assertThat(ctx.roleCode()).isNull();
            }
        }
    }

    // ─── Non-CustomUserDetails Principal ─────────────────────────────────────

    @Nested
    @DisplayName("when principal is not CustomUserDetails")
    class NonCustomUserDetailsPrincipalTests {

        @Test
        @DisplayName("returns true (continues chain) when principal is a plain string")
        void preHandle_stringPrincipal_continuesChain() {
            // After refactoring, non-CustomUserDetails principals are handled gracefully
            setAuthentication(
                    new UsernamePasswordAuthenticationToken("plainUser", "password"));

            boolean result = interceptor.preHandle(request, response, handler);
            assertThat(result).isTrue();
        }
    }

    // ─── HandlerInterceptor contract ─────────────────────────────────────────

    @Nested
    @DisplayName("HandlerInterceptor contract")
    class ContractTests {

        @Test
        @DisplayName("implements HandlerInterceptor")
        void implementsHandlerInterceptor() {
            assertThat(interceptor).isInstanceOf(HandlerInterceptor.class);
        }
    }
}
