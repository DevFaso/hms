package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Notification;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.repository.LabResultRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CriticalValueNotificationServiceTest {

    @Mock private NotificationService notificationService;
    @Mock private SmsService smsService;
    @Mock private LabResultRepository labResultRepository;
    @Mock private LabResultMapper labResultMapper;

    @InjectMocks private CriticalValueNotificationService service;

    private LabResult result;
    private User orderingUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "escalateAfterMinutes", 30L);

        orderingUser = new User();
        orderingUser.setId(UUID.randomUUID());
        orderingUser.setUsername("dr.diallo");
        orderingUser.setPhoneNumber("+22670707070");

        Staff orderingStaff = Staff.builder().build();
        orderingStaff.setUser(orderingUser);

        Patient patient = new Patient();
        patient.setFirstName("Bea");
        patient.setLastName("Ward");

        LabTestDefinition definition = new LabTestDefinition();
        definition.setName("Potassium");

        LabOrder order = new LabOrder();
        order.setOrderingStaff(orderingStaff);
        order.setPatient(patient);
        order.setLabTestDefinition(definition);

        result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue("7.1");
        result.setResultUnit("mmol/L");

        lenient().when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenReturn(new Notification());
        lenient().when(smsService.deliversRealSms()).thenReturn(false);
    }

    @Test
    void notifiesOrderingProviderForHl7CriticalFlag() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);

        service.notifyIfCritical(result);

        verify(notificationService).createNotification(
            contains("Potassium = 7.1 mmol/L"), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT"));
        assertThat(result.getCriticalNotifiedAt()).isNotNull();
        verify(labResultRepository).save(result);
    }

    @Test
    void notifiesForComputedHighSeverity() {
        when(labResultMapper.toResponseDTO(result))
            .thenReturn(LabResultResponseDTO.builder().severityFlag("HIGH").build());

        service.notifyIfCritical(result);

        verify(notificationService).createNotification(anyString(), eq("dr.diallo"), anyString());
    }

    @Test
    void skipsNormalResults() {
        when(labResultMapper.toResponseDTO(result))
            .thenReturn(LabResultResponseDTO.builder().severityFlag("NORMAL").build());

        service.notifyIfCritical(result);

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
        assertThat(result.getCriticalNotifiedAt()).isNull();
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void doesNotRenotifyAlreadyNotifiedResults() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(5));

        service.notifyIfCritical(result);

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void notificationFailureNeverPropagates() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("broker down"));

        service.notifyIfCritical(result); // must not throw
    }

    @Test
    void smsStaysOffWhileTransportIsMock() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        when(smsService.deliversRealSms()).thenReturn(false);

        service.notifyIfCritical(result);

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    void smsSentOverRealTransport() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        when(smsService.deliversRealSms()).thenReturn(true);

        service.notifyIfCritical(result);

        verify(smsService).send(eq("+22670707070"), contains("Potassium"));
    }

    @Test
    void stampsWithoutNotifyingWhenNoOrderingUser() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.getLabOrder().setOrderingStaff(null); // SYSTEM-actor path

        service.notifyIfCritical(result);

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
        assertThat(result.getCriticalNotifiedAt()).isNotNull(); // sweep convergence
        verify(labResultRepository).save(result);
    }

    @Test
    void escalateOverdueNotifiesAndStampsOnce() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(result));

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        verify(notificationService).createNotification(
            contains("ESCALATION"), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT_ESCALATION"));
        assertThat(result.getCriticalEscalatedAt()).isNotNull();
        verify(labResultRepository).save(result);
    }

    @Test
    void escalateOverdueStampsEvenWithoutResolvableUser() {
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        result.getLabOrder().setOrderingStaff(null);
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(result));

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        assertThat(result.getCriticalEscalatedAt()).isNotNull();
        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void escalateOverdueSkipsFailingRowAndContinues() {
        LabResult broken = new LabResult();
        broken.setId(UUID.randomUUID());
        broken.setLabOrder(result.getLabOrder());
        broken.setResultValue("x");
        broken.setCriticalNotifiedAt(LocalDateTime.now().minusHours(2));
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));

        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(broken, result));
        doThrow(new IllegalStateException("boom")).when(labResultRepository).save(broken);

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1); // broken row skipped, second processed
        verify(labResultRepository).save(result);
    }
}
