package com.example.hms.controller;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.payload.dto.superadmin.IntegrationMessageEventDTO;
import com.example.hms.payload.dto.superadmin.IntegrationMessagePageDTO;
import com.example.hms.service.SuperAdminIntegrationMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminIntegrationMessageControllerTest {

    @Mock
    private SuperAdminIntegrationMessageService service;

    @InjectMocks
    private SuperAdminIntegrationMessageController controller;

    @Test
    void searchClampsPageSizeToMaxAndForwardsFiltersToService() {
        IntegrationMessagePageDTO page = IntegrationMessagePageDTO.builder()
            .content(List.of())
            .pageNumber(0)
            .pageSize(25)
            .totalElements(0L)
            .totalPages(0)
            .deadLetterCount(0L)
            .build();
        when(service.search(eq("partner.nhis"), isNull(),
            eq(IntegrationMessageStatus.FAILED), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(page);

        ResponseEntity<IntegrationMessagePageDTO> response = controller.search(
            "partner.nhis", null, IntegrationMessageStatus.FAILED, null, null,
            /* page */ -3, /* size */ Integer.MAX_VALUE);

        assertThat(response.getBody()).isSameAs(page);

        // Negative page clamps to 0. Oversized pageSize clamps to the
        // controller's MAX_PAGE_SIZE (lowered to 200 in the Copilot
        // review fix — 200 rows × 64 KB payload ceiling caps a worst-
        // case search response at ~12 MB).
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).search(eq("partner.nhis"), isNull(),
            eq(IntegrationMessageStatus.FAILED), isNull(), isNull(),
            pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void searchUsesDefaultPageSizeWhenNothingProvided() {
        IntegrationMessagePageDTO page = IntegrationMessagePageDTO.builder()
            .content(List.of()).pageNumber(0).pageSize(25).totalElements(0L)
            .totalPages(0).deadLetterCount(0L).build();
        when(service.search(isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(page);

        controller.search(null, null, null, null, null, 0, 25);

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(service).search(isNull(), isNull(), isNull(), isNull(), isNull(), cap.capture());
        assertThat(cap.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    void searchClampsPageSizeMinimumToOne() {
        // size=0 is nonsensical for a paged result; controller clamps to
        // 1 so the underlying PageRequest constructor doesn't blow up.
        IntegrationMessagePageDTO page = IntegrationMessagePageDTO.builder()
            .content(List.of()).pageNumber(0).pageSize(1).totalElements(0L)
            .totalPages(0).deadLetterCount(0L).build();
        when(service.search(isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(page);

        controller.search(null, null, null, null, null, 0, 0);

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(service).search(isNull(), isNull(), isNull(), isNull(), isNull(), cap.capture());
        assertThat(cap.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void getByIdDelegatesToServiceAndReturnsTheRowWithPayload() {
        UUID id = UUID.randomUUID();
        IntegrationMessageEventDTO dto = IntegrationMessageEventDTO.builder()
            .id(id)
            .integrationId("partner.nhis")
            .direction(IntegrationMessageDirection.INBOUND)
            .payload("{\"full\":\"payload\"}")
            .status(IntegrationMessageStatus.RECEIVED)
            .attemptCount(1)
            .receivedAt(LocalDateTime.now())
            .lastAttemptedAt(LocalDateTime.now())
            .build();
        when(service.getById(id)).thenReturn(dto);

        ResponseEntity<IntegrationMessageEventDTO> response = controller.getById(id);

        assertThat(response.getBody()).isSameAs(dto);
        verify(service).getById(id);
    }

    @Test
    void replayDelegatesToServiceAndReturnsTheReplayDto() {
        UUID id = UUID.randomUUID();
        IntegrationMessageEventDTO dto = IntegrationMessageEventDTO.builder()
            .id(UUID.randomUUID())
            .integrationId("partner.nhis")
            .direction(IntegrationMessageDirection.OUTBOUND)
            .status(IntegrationMessageStatus.REPLAYED)
            .attemptCount(2)
            .receivedAt(LocalDateTime.now())
            .lastAttemptedAt(LocalDateTime.now())
            .build();
        when(service.replay(id)).thenReturn(dto);

        ResponseEntity<IntegrationMessageEventDTO> response = controller.replay(id);

        assertThat(response.getBody()).isSameAs(dto);
        verify(service).replay(id);
    }
}
