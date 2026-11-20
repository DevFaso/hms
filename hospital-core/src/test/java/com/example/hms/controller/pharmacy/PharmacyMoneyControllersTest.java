package com.example.hms.controller.pharmacy;

import com.example.hms.enums.PharmacyRefundStatus;
import com.example.hms.payload.dto.pharmacy.DispensePaymentStatusDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemResponseDTO;
import com.example.hms.payload.dto.pharmacy.PackSizeRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyReconciliationDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundPayRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundReasonRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundRequestDTO;
import com.example.hms.service.pharmacy.DispenseService;
import com.example.hms.service.pharmacy.InventoryService;
import com.example.hms.service.pharmacy.PharmacyReconciliationService;
import com.example.hms.service.pharmacy.PharmacyRefundService;
import com.example.hms.service.pharmacy.PrescriptionQueueClaimService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** QA r65 D2673–D2676: the pharmacy money endpoints hand the service's answer back as it is. */
@DisplayName("Pharmacy money controllers (QA D2673-D2676)")
class PharmacyMoneyControllersTest {

    private final UUID id = UUID.randomUUID();

    @Test
    @DisplayName("refunds: list, request (201), approve, reject, pay, cancel delegate to the service")
    void refunds() {
        PharmacyRefundService service = mock(PharmacyRefundService.class);
        PharmacyRefundController controller = new PharmacyRefundController(service);
        PharmacyRefundDTO dto = PharmacyRefundDTO.builder().id(id).status("REQUESTED").build();
        PharmacyRefundRequestDTO request = PharmacyRefundRequestDTO.builder().returnTransactionId(id).reason("x").build();
        PharmacyRefundReasonRequestDTO reason = new PharmacyRefundReasonRequestDTO("motif");
        PharmacyRefundPayRequestDTO pay = new PharmacyRefundPayRequestDTO();
        List<PharmacyRefundStatus> statuses = List.of(PharmacyRefundStatus.REQUESTED);
        when(service.list(statuses)).thenReturn(List.of(dto));
        when(service.request(request)).thenReturn(dto);
        when(service.approve(id)).thenReturn(dto);
        when(service.reject(id, reason)).thenReturn(dto);
        when(service.pay(id, pay)).thenReturn(dto);
        when(service.cancel(id, reason)).thenReturn(dto);

        assertThat(controller.list(statuses).getBody().getData()).containsExactly(dto);
        ResponseEntity<?> created = controller.request(request);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.approve(id).getBody().getData()).isSameAs(dto);
        assertThat(controller.reject(id, reason).getBody().getData()).isSameAs(dto);
        assertThat(controller.pay(id, pay).getBody().getData()).isSameAs(dto);
        assertThat(controller.cancel(id, reason).getBody().getData()).isSameAs(dto);
    }

    @Test
    @DisplayName("reconciliation: the day, the pharmacies, and the CSV as a never-cached attachment named by the day")
    void reconciliation() {
        PharmacyReconciliationService service = mock(PharmacyReconciliationService.class);
        PharmacyReconciliationController controller = new PharmacyReconciliationController(service);
        LocalDate day = LocalDate.of(2026, 11, 20);
        PharmacyReconciliationDTO dto = PharmacyReconciliationDTO.builder().pharmacyId(id).date(day).build();
        when(service.daily(id, day)).thenReturn(dto);
        when(service.pharmacies()).thenReturn(List.of(dto));
        when(service.dayOf(null)).thenReturn(day);
        when(service.exportCsv(id, day)).thenReturn("x".getBytes(StandardCharsets.UTF_8));

        assertThat(controller.daily(id, day).getBody().getData()).isSameAs(dto);
        assertThat(controller.pharmacies().getBody().getData()).containsExactly(dto);
        ResponseEntity<byte[]> csv = controller.export(id, null);
        assertThat(csv.getBody()).isEqualTo("x".getBytes(StandardCharsets.UTF_8));
        assertThat(csv.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("rapprochement-pharmacie-2026-11-20.csv");
        assertThat(csv.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    @DisplayName("a fill's payment status and an item's pack size delegate to their services")
    void paymentStatusAndPackSize() {
        DispenseService dispenseService = mock(DispenseService.class);
        DispenseController dispenseController = new DispenseController(dispenseService,
                mock(PrescriptionQueueClaimService.class));
        DispensePaymentStatusDTO status = DispensePaymentStatusDTO.builder().dispenseId(id).status("UNPAID").build();
        when(dispenseService.getPaymentStatus(id)).thenReturn(status);
        assertThat(dispenseController.paymentStatus(id).getBody().getData()).isSameAs(status);

        InventoryService inventoryService = mock(InventoryService.class);
        InventoryController inventoryController = new InventoryController(inventoryService);
        PackSizeRequestDTO pack = PackSizeRequestDTO.builder().unitsPerPack(10).unit("comprimé").reason("r").build();
        InventoryItemResponseDTO item = InventoryItemResponseDTO.builder().id(id).unitsPerPack(10).build();
        when(inventoryService.setPackSize(id, pack)).thenReturn(item);
        assertThat(inventoryController.setPackSize(id, pack).getBody().getData()).isSameAs(item);
    }
}
