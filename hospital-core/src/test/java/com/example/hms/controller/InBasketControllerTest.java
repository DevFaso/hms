package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.clinical.InBasketItemDTO;
import com.example.hms.payload.dto.clinical.InBasketSummaryDTO;
import com.example.hms.service.InBasketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the super-admin global-view contract on
 * {@link InBasketController}.
 *
 * <p>Reproduces the production failure where {@code GET /api/in-basket} and
 * {@code GET /api/in-basket/summary} returned HTTP 400 ("Hospital context is
 * required") for a super-admin in global view: the frontend's auth
 * interceptor doesn't send {@code X-Hospital-Id} for super-admins by design,
 * the JWT carries no hospital scope, and {@code resolveHospital()}
 * therefore returned null. The controller used to throw
 * {@code BusinessException} regardless of role.
 *
 * <p>The fix lets the null hospitalId fall through for super-admins; the
 * repository's JPQL drops the hospital filter and returns every item
 * addressed to the recipient (recipientUser.id stays the access-control
 * predicate). Non-super-admin clinicians still 400 — a missing hospital
 * scope on a clinical token is a misconfiguration, not a feature.
 *
 * <p>Direct unit test (not a {@code @WebMvcTest} slice) because the
 * controller takes {@link Authentication} as a method parameter and
 * Spring's argument resolver does not run with {@code addFilters=false}
 * in a slice — the parameter would resolve to {@code null} and obscure
 * the behaviour we want to pin.
 */
@ExtendWith(MockitoExtension.class)
class InBasketControllerTest {

    @Mock private InBasketService inBasketService;
    @Mock private ControllerAuthUtils authUtils;

    @InjectMocks private InBasketController controller;

    private static final UUID USER_ID = UUID.randomUUID();

    private Authentication authFor(String... roles) {
        return new UsernamePasswordAuthenticationToken(
            "user", "jwt", AuthorityUtils.createAuthorityList(roles));
    }

    @BeforeEach
    void stubUserResolution() {
        when(authUtils.resolveUserId(any(Authentication.class)))
            .thenReturn(Optional.of(USER_ID));
    }

    // ─── Regression: super-admin global view must not 400 ─────────────────────

    @Test
    void list_superAdminWithoutHospitalId_returns200() {
        Authentication auth = authFor("ROLE_SUPER_ADMIN");
        when(authUtils.resolveHospitalScope(eq(auth), isNull(), isNull(), eq(false)))
            .thenReturn(null);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(true);
        Page<InBasketItemDTO> empty = new PageImpl<>(java.util.List.of());
        when(inBasketService.getItems(eq(USER_ID), isNull(), any(), any(), any(Pageable.class)))
            .thenReturn(empty);

        ResponseEntity<Page<InBasketItemDTO>> response = controller.list(
            null, null, null, Pageable.ofSize(20), auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // null hospitalId must be carried through to the service so the
        // repository's JPQL can drop its hospital filter.
        verify(inBasketService).getItems(eq(USER_ID), isNull(), any(), any(), any(Pageable.class));
    }

    @Test
    void summary_superAdminWithoutHospitalId_returns200() {
        Authentication auth = authFor("ROLE_SUPER_ADMIN");
        when(authUtils.resolveHospitalScope(eq(auth), isNull(), isNull(), eq(false)))
            .thenReturn(null);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(true);
        when(inBasketService.getSummary(eq(USER_ID), isNull()))
            .thenReturn(InBasketSummaryDTO.builder().totalUnread(0L).build());

        ResponseEntity<InBasketSummaryDTO> response = controller.summary(null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalUnread()).isZero();
        verify(inBasketService).getSummary(eq(USER_ID), isNull());
    }

    // ─── Guard: non-super-admin without scope still 400s ──────────────────────

    @Test
    void list_clinicianWithoutHospitalScope_stillThrowsBusinessException() {
        Authentication auth = authFor("ROLE_DOCTOR");
        Pageable pageable = Pageable.ofSize(20);
        when(authUtils.resolveHospitalScope(eq(auth), isNull(), isNull(), eq(false)))
            .thenReturn(null);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(false);

        assertThatThrownBy(() -> controller.list(null, null, null, pageable, auth))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context is required");
    }

    @Test
    void summary_clinicianWithoutHospitalScope_stillThrowsBusinessException() {
        Authentication auth = authFor("ROLE_DOCTOR");
        when(authUtils.resolveHospitalScope(eq(auth), isNull(), isNull(), eq(false)))
            .thenReturn(null);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(false);

        assertThatThrownBy(() -> controller.summary(null, auth))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context is required");
    }
}
