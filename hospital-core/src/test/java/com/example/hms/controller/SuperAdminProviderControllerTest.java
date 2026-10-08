package com.example.hms.controller;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.payload.dto.provider.ProviderCreateRequestDTO;
import com.example.hms.payload.dto.provider.ProviderDecisionRequestDTO;
import com.example.hms.payload.dto.provider.ProviderResponseDTO;
import com.example.hms.payload.dto.provider.ProviderResubmitRequestDTO;
import com.example.hms.payload.dto.provider.ProviderVerifyRequestDTO;
import com.example.hms.service.provider.ProviderOnboardingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** The super-admin providers endpoints delegate to the service, and every handler is super-admin only. */
@ExtendWith(MockitoExtension.class)
class SuperAdminProviderControllerTest {

    @Mock private ProviderOnboardingService service;
    @InjectMocks private SuperAdminProviderController controller;

    private final UUID id = UUID.randomUUID();
    private final ProviderResponseDTO dto = ProviderResponseDTO.builder().id(id).build();

    @Test
    @DisplayName("every handler is guarded by the SUPER_ADMIN authority (the service then requires a verified one)")
    void everyHandlerIsSuperAdminOnly() {
        List<Method> handlers = Arrays.stream(SuperAdminProviderController.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class))
            .toList();
        assertThat(handlers).hasSize(7);
        assertThat(handlers).allSatisfy(m -> assertThat(m.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('ROLE_SUPER_ADMIN')"));
    }

    @Test
    void createAnswers201() {
        ProviderCreateRequestDTO request = new ProviderCreateRequestDTO();
        when(service.create(request)).thenReturn(dto);

        ResponseEntity<ProviderResponseDTO> response = controller.create(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(dto);
    }

    @Test
    void listGetAndDecisionsDelegate() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<ProviderResponseDTO> page = new PageImpl<>(List.of(dto));
        when(service.list(FacilityType.PHARMACY, ProviderVerificationStatus.SUBMITTED, pageable)).thenReturn(page);
        when(service.get(id)).thenReturn(dto);
        ProviderVerifyRequestDTO verify = new ProviderVerifyRequestDTO();
        ProviderDecisionRequestDTO decision = new ProviderDecisionRequestDTO("reason");
        ProviderResubmitRequestDTO resubmit = new ProviderResubmitRequestDTO();
        when(service.verify(id, verify)).thenReturn(dto);
        when(service.reject(id, decision)).thenReturn(dto);
        when(service.revoke(id, decision)).thenReturn(dto);
        when(service.resubmit(id, resubmit)).thenReturn(dto);

        assertThat(controller.list(FacilityType.PHARMACY, ProviderVerificationStatus.SUBMITTED, pageable).getBody())
            .isSameAs(page);
        assertThat(controller.get(id).getBody()).isSameAs(dto);
        assertThat(controller.verify(id, verify).getBody()).isSameAs(dto);
        assertThat(controller.reject(id, decision).getBody()).isSameAs(dto);
        assertThat(controller.revoke(id, decision).getBody()).isSameAs(dto);
        assertThat(controller.resubmit(id, resubmit).getBody()).isSameAs(dto);
    }
}
