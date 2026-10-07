package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseSettingsDTO;
import com.example.hms.service.pharmacy.DispenseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        assertThat(gateOf("getSettings")).isEqualTo(gateOf("getWorkQueue", Pageable.class));
    }

    @Test
    @DisplayName("GET /settings reports the ready-for-collection flag")
    void settingsReportTheFlag() {
        DispenseService service = mock(DispenseService.class);
        when(service.isReadyForCollectionEnabled()).thenReturn(false, true);
        DispenseController controller = new DispenseController(service);

        DispenseSettingsDTO off = controller.getSettings().getBody().getData();
        DispenseSettingsDTO on = controller.getSettings().getBody().getData();

        assertThat(off.isReadyForCollectionEnabled()).isFalse();
        assertThat(on.isReadyForCollectionEnabled()).isTrue();
    }
}
