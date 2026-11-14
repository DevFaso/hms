package com.example.hms.mapper;

import com.example.hms.controller.LabOrderController;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabOrderDuplicateMatchDTO;
import com.example.hms.service.LabOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The duplicate warning's summary of an earlier order, and the preview endpoint that serves it. */
class LabOrderDuplicateMatchMapperTest {

    private final LabOrderMapper mapper = new LabOrderMapper();

    @Test
    void summarisesTheEarlierOrder() {
        LabTestDefinition glucose = new LabTestDefinition();
        glucose.setId(UUID.randomUUID());
        glucose.setTestCode("GLU");
        glucose.setName("Fasting glucose");
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(glucose);
        order.setStatus(LabOrderStatus.COLLECTED);
        order.setPriority("STAT");
        LocalDateTime at = LocalDateTime.of(2026, 11, 13, 8, 30);
        order.setOrderDatetime(at);

        LabOrderDuplicateMatchDTO match = mapper.toDuplicateMatchDTO(order);

        assertThat(match.getOrderId()).isEqualTo(order.getId());
        assertThat(match.getLabOrderCode()).isEqualTo(order.getId().toString());
        assertThat(match.getLabTestName()).isEqualTo("Fasting glucose");
        assertThat(match.getLabTestCode()).isEqualTo("GLU");
        assertThat(match.getStatus()).isEqualTo("COLLECTED");
        assertThat(match.getPriority()).isEqualTo("STAT");
        assertThat(match.getOrderDatetime()).isEqualTo(at);
    }

    @Test
    void anOrderWhoseTestWasDeletedIsLabelledAndNullIsNull() {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());

        LabOrderDuplicateMatchDTO match = mapper.toDuplicateMatchDTO(order);

        assertThat(match.getLabTestName()).isNotBlank();
        assertThat(match.getLabTestCode()).isNull();
        assertThat(match.getStatus()).isNull();
        assertThat(mapper.toDuplicateMatchDTO(null)).isNull();
    }

    @Test
    void thePreviewEndpointReturnsTheServicesMatches() {
        LabOrderService service = mock(LabOrderService.class);
        UUID patientId = UUID.randomUUID();
        UUID testId = UUID.randomUUID();
        LabOrderDuplicateMatchDTO match = LabOrderDuplicateMatchDTO.builder().orderId(UUID.randomUUID()).build();
        when(service.previewDuplicates(patientId, null, testId)).thenReturn(List.of(match));
        LabOrderController controller = new LabOrderController(service, mock(MessageSource.class));

        assertThat(controller.previewDuplicates(patientId, testId, null).getBody().getData()).containsExactly(match);
    }
}
