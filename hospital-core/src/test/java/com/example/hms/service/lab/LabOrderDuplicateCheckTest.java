package com.example.hms.service.lab;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.LabOrderDuplicateMatchDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The lab-order duplicate warning: what it asks the database, and what it reports. */
@ExtendWith(MockitoExtension.class)
class LabOrderDuplicateCheckTest {

    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabOrderMapper labOrderMapper;
    @Mock private AuditEventLogService auditEventLogService;

    @InjectMocks private LabOrderDuplicateCheck check;

    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID definitionId = UUID.randomUUID();

    @Test
    void asksForActiveOrdersOfTheSameTestHereWithinTheWindow() {
        LabOrder earlier = order(UUID.randomUUID());
        when(labOrderRepository.findActiveSameTestSince(eq(patientId), eq(hospitalId), eq(definitionId),
            eq("GLU-F"), any(), any(), any())).thenReturn(List.of(earlier));

        LabOrder fresh = order(null);
        fresh.getLabTestDefinition().setTestCode(" glu-f ");
        LocalDateTime before = LocalDateTime.now();
        assertThat(check.find(fresh)).containsExactly(earlier);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<LabOrderStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(labOrderRepository).findActiveSameTestSince(eq(patientId), eq(hospitalId), eq(definitionId),
            eq("GLU-F"), statuses.capture(), since.capture(), any());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(LabOrderStatus.ORDERED, LabOrderStatus.PENDING,
            LabOrderStatus.COLLECTED, LabOrderStatus.RECEIVED, LabOrderStatus.IN_PROGRESS);
        assertThat(Duration.between(since.getValue(), before)).isBetween(Duration.ofHours(24).minusMinutes(1),
            Duration.ofHours(24).plusMinutes(1));
    }

    @Test
    void anAcknowledgedDuplicateIsFlaggedOnTheResponseAndRecordedOnTheOrdersTrail() {
        LabOrder earlier = order(UUID.randomUUID());
        LabOrder saved = order(UUID.randomUUID());
        LabOrderDuplicateMatchDTO match = LabOrderDuplicateMatchDTO.builder().orderId(earlier.getId()).build();
        when(labOrderMapper.toDuplicateMatchDTO(earlier)).thenReturn(match);
        UUID actor = UUID.randomUUID();
        LabOrderResponseDTO response = LabOrderResponseDTO.builder().build();

        check.report(saved, List.of(earlier), true, response, actor);

        assertThat(response.getDuplicateOfRecentOrder()).isTrue();
        assertThat(response.getDuplicateReferenceOrderId()).isEqualTo(earlier.getId());
        assertThat(response.getDuplicateMatches()).containsExactly(match);
        assertThat(response.getDuplicateAcknowledged()).isTrue();
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.LAB_ORDER_DUPLICATE_PLACED);
        assertThat(audit.getValue().getResourceId()).isEqualTo(saved.getId().toString());
        assertThat(audit.getValue().getPatientId()).isEqualTo(patientId);
        assertThat(audit.getValue().getHospitalId()).isEqualTo(hospitalId);
        assertThat(audit.getValue().getUserId()).isEqualTo(actor);
        assertThat(audit.getValue().getEventDescription()).contains("acknowledged (order anyway)")
            .contains(earlier.getId().toString());
    }

    @Test
    void anUnacknowledgedDuplicateIsRecordedAsSuch() {
        LabOrder earlier = order(UUID.randomUUID());
        LabOrderResponseDTO response = LabOrderResponseDTO.builder().build();

        check.report(order(UUID.randomUUID()), List.of(earlier), false, response, null);

        assertThat(response.getDuplicateOfRecentOrder()).isTrue();
        assertThat(response.getDuplicateAcknowledged()).isFalse();
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventDescription()).startsWith("Duplicate warning not acknowledged");
    }

    @Test
    void noMatchMeansNoWarningAndNothingRecorded() {
        LabOrderResponseDTO response = LabOrderResponseDTO.builder().build();

        check.report(order(UUID.randomUUID()), List.of(), true, response, UUID.randomUUID());

        assertThat(response.getDuplicateOfRecentOrder()).isFalse();
        assertThat(response.getDuplicateMatches()).isEmpty();
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    void anOrderWithoutPatientHospitalOrTestAsksNothing() {
        LabOrder incomplete = order(null);
        incomplete.setHospital(null);

        assertThat(check.find(incomplete)).isEmpty();
        verify(labOrderRepository, never()).findActiveSameTestSince(any(), any(), any(), any(), any(), any(), any());
    }

    private LabOrder order(UUID id) {
        Patient patient = new Patient();
        patient.setId(patientId);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(definitionId);
        definition.setTestCode("GLU-F");
        LabOrder order = new LabOrder();
        order.setId(id);
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setLabTestDefinition(definition);
        return order;
    }
}
