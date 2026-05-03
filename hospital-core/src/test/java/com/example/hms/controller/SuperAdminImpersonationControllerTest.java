package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.ImpersonationActiveResponseDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartRequestDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartResponseDTO;
import com.example.hms.service.SupportImpersonationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminImpersonationControllerTest {

    @Mock
    private SupportImpersonationService service;

    @InjectMocks
    private SuperAdminImpersonationController controller;

    @Test
    void startReturns201WithToken() {
        ImpersonationStartRequestDTO request = ImpersonationStartRequestDTO.builder()
            .targetUserId(UUID.randomUUID())
            .reason("validating refill bug")
            .build();
        ImpersonationStartResponseDTO response = ImpersonationStartResponseDTO.builder()
            .accessToken("jwt")
            .expiresAt(Instant.now().plusSeconds(1800))
            .build();
        when(service.start(request, "654321")).thenReturn(response);

        ResponseEntity<ImpersonationStartResponseDTO> entity = controller.start(request, "654321");

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(entity.getBody()).isSameAs(response);
        verify(service).start(request, "654321");
    }

    @Test
    void stopReturnsServiceResponse() {
        ImpersonationActiveResponseDTO response = ImpersonationActiveResponseDTO.builder()
            .impersonating(false)
            .build();
        when(service.stop()).thenReturn(response);

        ResponseEntity<ImpersonationActiveResponseDTO> entity = controller.stop();

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isSameAs(response);
    }

    @Test
    void activeReturnsServiceResponse() {
        ImpersonationActiveResponseDTO response = ImpersonationActiveResponseDTO.builder()
            .impersonating(true)
            .impersonatorUsername("super.admin")
            .targetUsername("nurse.alice")
            .build();
        when(service.getActive()).thenReturn(response);

        ResponseEntity<ImpersonationActiveResponseDTO> entity = controller.active();

        assertThat(entity.getBody()).isSameAs(response);
    }
}
