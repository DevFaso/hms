package com.example.hms.controller.pharmacy;

import com.example.hms.enums.QueueClaimFilter;
import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseSettingsDTO;
import com.example.hms.service.pharmacy.DispenseService;
import com.example.hms.service.pharmacy.PrescriptionQueueClaimService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G15 AC-14 / AC-17: the ready-for-collection endpoints carry exactly the
 * gate of {@code POST /pharmacy/dispense}, and the settings read exactly the
 * gate of the work queue. Asserted by reflection: a {@code @WebMvcTest}
 * slice adds nothing here (see the webmvctest-slice-scanning lesson).
 */
class DispenseControllerTest {

    private static String gateOf(String methodName, Class<?>... paramTypes) throws Exception {
        Method method = DispenseController.class.getDeclaredMethod(methodName, paramTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("@PreAuthorize on %s", methodName).isNotNull();
        return annotation.value();
    }

    @Test
    @DisplayName("POST /ready has the gate of POST /pharmacy/dispense")
    void readyHasTheDispenseGate() throws Exception {
        assertThat(gateOf("markReady", DispenseRequestDTO.class))
                .isEqualTo(gateOf("dispense", DispenseRequestDTO.class));
    }

    @Test
    @DisplayName("hand-over and cancel-ready have the gate of POST /pharmacy/dispense")
    void handOverAndCancelReadyHaveTheDispenseGate() throws Exception {
        String dispenseGate = gateOf("dispense", DispenseRequestDTO.class);
        assertThat(gateOf("handOver", java.util.UUID.class,
                com.example.hms.payload.dto.pharmacy.HandOverRequestDTO.class)).isEqualTo(dispenseGate);
        assertThat(gateOf("cancelReady", java.util.UUID.class,
                com.example.hms.payload.dto.pharmacy.CancelReadyRequestDTO.class)).isEqualTo(dispenseGate);
    }

    @Test
    @DisplayName("GET /settings has the gate of the work queue")
    void settingsHaveTheWorkQueueGate() throws Exception {
        assertThat(gateOf("getSettings")).isEqualTo(gateOf("getWorkQueue", Pageable.class, QueueClaimFilter.class));
    }

    @Test
    @DisplayName("G13 AC-14: claim, take-over and release have exactly the gate of the work queue")
    void claimEndpointsHaveTheWorkQueueGate() throws Exception {
        String queueGate = gateOf("getWorkQueue", Pageable.class, QueueClaimFilter.class);
        assertThat(gateOf("claimQueueRow", UUID.class)).isEqualTo(queueGate);
        assertThat(gateOf("takeOverQueueRow", UUID.class)).isEqualTo(queueGate);
        assertThat(gateOf("releaseQueueRow", UUID.class)).isEqualTo(queueGate);
    }

    @Test
    @DisplayName("G13 AC-16: GET /settings reports the claim flag and the TTL in minutes")
    void settingsReportTheClaimFlagAndTtl() {
        DispenseService service = mock(DispenseService.class);
        PrescriptionQueueClaimService claims = mock(PrescriptionQueueClaimService.class);
        when(claims.isEnabled()).thenReturn(true);
        when(claims.ttl()).thenReturn(Duration.ofMinutes(15));

        DispenseSettingsDTO settings = new DispenseController(service, claims).getSettings().getBody().getData();

        assertThat(settings.isQueueClaimEnabled()).isTrue();
        assertThat(settings.getQueueClaimTtlMinutes()).isEqualTo(15);
    }

    @Test
    @DisplayName("G13 AC-13: claim= binds to the filter, defaults to ALL, and an unknown value is a 400")
    void claimFilterBinding() throws Exception {
        DispenseService service = mock(DispenseService.class);
        PrescriptionQueueClaimService claims = mock(PrescriptionQueueClaimService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DispenseController(service, claims))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/pharmacy/dispense/work-queue").param("claim", "MINE")).andExpect(status().isOk());
        verify(service).getWorkQueue(any(Pageable.class), eq(QueueClaimFilter.MINE));
        mvc.perform(get("/pharmacy/dispense/work-queue")).andExpect(status().isOk());
        verify(service).getWorkQueue(any(Pageable.class), eq(QueueClaimFilter.ALL));

        mvc.perform(get("/pharmacy/dispense/work-queue").param("claim", "BOGUS"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(claims);
    }

    @Test
    @DisplayName("GET /settings reports the ready-for-collection flag")
    void settingsReportTheFlag() {
        DispenseService service = mock(DispenseService.class);
        when(service.isReadyForCollectionEnabled()).thenReturn(false, true);
        PrescriptionQueueClaimService claims = mock(PrescriptionQueueClaimService.class);
        when(claims.ttl()).thenReturn(Duration.ofMinutes(15));
        DispenseController controller = new DispenseController(service, claims);

        DispenseSettingsDTO off = controller.getSettings().getBody().getData();
        DispenseSettingsDTO on = controller.getSettings().getBody().getData();

        assertThat(off.isReadyForCollectionEnabled()).isFalse();
        assertThat(on.isReadyForCollectionEnabled()).isTrue();
    }
}
