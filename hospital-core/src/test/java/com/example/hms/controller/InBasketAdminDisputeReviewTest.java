package com.example.hms.controller;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.clinical.InBasketSummaryDTO;
import com.example.hms.service.InBasketService;
import com.example.hms.controller.support.ControllerAuthUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one exception to E9 #67: a hospital administrator with no clinical role opens the in-basket and reads only the
 * review of a disputed death addressed to them. Every entry of the controller is walked. The repository's own filter
 * on recipient and reference type is on real PostgreSQL in {@code InBasketDisputeReviewPostgresIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InBasketAdminDisputeReviewTest {

    @Mock private InBasketService inBasketService;
    @Mock private ControllerAuthUtils authUtils;
    @InjectMocks private InBasketController controller;

    private final Authentication auth = mock(Authentication.class);
    private final UUID user = UUID.randomUUID();
    private final UUID hospital = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(user));
        when(authUtils.resolveHospitalScope(any(), any(), any(), any(boolean.class))).thenReturn(hospital);
        when(inBasketService.getItems(any(), any(), any(), any(), any())).thenReturn(Page.empty());
        when(inBasketService.getDisputeReviewItems(any(), any(), any(), any())).thenReturn(Page.empty());
        when(inBasketService.getSummary(any(), any())).thenReturn(InBasketSummaryDTO.builder().build());
        when(inBasketService.getDisputeReviewSummary(any(), any())).thenReturn(InBasketSummaryDTO.builder().build());
    }

    private void holds(String... roles) {
        when(authUtils.hasAuthority(eq(auth), anyString())).thenAnswer(call -> java.util.Arrays.asList(roles).contains(call.getArgument(1)));
    }

    @Test
    @DisplayName("an administrator's list and summary are the dispute reviews, never the rest")
    void administratorReadsTheDisputeReviewOnly() {
        holds("ROLE_HOSPITAL_ADMIN");

        controller.list(null, null, hospital, Pageable.unpaged(), auth);
        controller.summary(hospital, auth);

        verify(inBasketService).getDisputeReviewItems(eq(user), any(), any(), any());
        verify(inBasketService).getDisputeReviewSummary(eq(user), any());
        verify(inBasketService, never()).getItems(any(), any(), any(), any(), any());
        verify(inBasketService, never()).getSummary(any(), any());
    }

    @Test
    @DisplayName("an administrator cannot touch any other item, even one addressed to them")
    void administratorTouchesNoOtherItem() {
        holds("ROLE_HOSPITAL_ADMIN");
        UUID item = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("inbasket.item.notFound", item)).when(inBasketService).requireDisputeReviewItem(item, user);

        assertThatThrownBy(() -> controller.markAsRead(item, auth)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.acknowledge(item, auth)).isInstanceOf(ResourceNotFoundException.class);

        verify(inBasketService, never()).markAsRead(any(), any());
        verify(inBasketService, never()).acknowledge(any(), any());
    }

    @Test
    @DisplayName("an administrator who is also a clinician keeps a clinician's in-basket; a clinician is unchanged")
    void aClinicianIsUnchanged() {
        holds("ROLE_HOSPITAL_ADMIN", "ROLE_DOCTOR");

        controller.list(null, null, hospital, Pageable.unpaged(), auth);
        controller.markAsRead(UUID.randomUUID(), auth);

        verify(inBasketService).getItems(eq(user), any(), any(), any(), any());
        verify(inBasketService, never()).requireDisputeReviewItem(any(), any());
    }
}
