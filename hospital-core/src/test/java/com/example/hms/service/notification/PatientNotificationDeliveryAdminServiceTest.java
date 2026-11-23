package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.payload.dto.notification.PatientNotificationDeliveryPageDTO;
import com.example.hms.repository.PatientNotificationDeliveryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Patient notification sends: the hospital administrators' view and retry")
class PatientNotificationDeliveryAdminServiceTest {

    @Mock private PatientNotificationDeliveryRepository repository;
    @Mock private PatientRepository patientRepository;
    @Mock private com.example.hms.repository.UserRepository userRepository;
    @Mock private ActingScopeResolver actingScopeResolver;
    @Mock private RoleValidator roleValidator;
    @Mock private com.example.hms.repository.AppointmentRepository appointmentRepository;
    @Mock private com.example.hms.repository.BillingInvoiceRepository invoiceRepository;
    @Mock private com.example.hms.repository.LabOrderRepository labOrderRepository;

    private PatientNotificationDeliveryAdminService service;
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private PatientNotificationDelivery failed;
    private Patient patient;

    @BeforeEach
    void setUp() {
        service = new PatientNotificationDeliveryAdminService(repository, patientRepository, userRepository, appointmentRepository,
            invoiceRepository, labOrderRepository, actingScopeResolver,
            roleValidator, Clock.fixed(Instant.parse("2026-11-17T08:00:00Z"), ZoneOffset.UTC));
        lenient().when(actingScopeResolver.requirePinned()).thenReturn(hospitalId);
        lenient().when(roleValidator.getCurrentUserId()).thenReturn(adminId);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("Traore");
        failed = PatientNotificationDelivery.builder().hospitalId(hospitalId).patientId(patient.getId())
            .eventType(PatientNotificationEvent.LAB_RESULT_AVAILABLE).channel(NotificationChannel.SMS)
            .status(NotificationDeliveryStatus.FAILED).attempts(5).errorCode("PROVIDER_UNREACHABLE").build();
        failed.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("FAILED is the default filter, scoped to the pinned hospital, with every status counted")
    @SuppressWarnings("unchecked")
    void listsFailedOfThisHospital() {
        when(repository.searchForHospital(eq(hospitalId), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(failed), PageRequest.of(0, 25), 1));
        when(repository.countByStatusForHospital(hospitalId))
            .thenReturn(List.<Object[]>of(new Object[] {NotificationDeliveryStatus.FAILED, 1L}));
        when(patientRepository.findAllById(any())).thenReturn(List.of(patient));

        PatientNotificationDeliveryPageDTO page = service.search(null, 0, 25);

        ArgumentCaptor<Collection<NotificationDeliveryStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(repository).searchForHospital(eq(hospitalId), statuses.capture(), any(Pageable.class));
        assertThat(statuses.getValue()).containsExactly(NotificationDeliveryStatus.FAILED);
        assertThat(page.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.getPatientName()).contains("Awa");
            assertThat(dto.getErrorCode()).isEqualTo("PROVIDER_UNREACHABLE");
            assertThat(dto.isRetryable()).isTrue();
        });
        assertThat(page.getCountsByStatus()).containsEntry("FAILED", 1L).containsEntry("SENT", 0L).hasSize(5);
    }

    @Test
    @DisplayName("QA D2402: an opt-out row says what it was about: the appointment, the invoice, at this hospital only")
    void describesWhatEachRowWasAbout() {
        com.example.hms.model.Hospital here = new com.example.hms.model.Hospital();
        here.setId(hospitalId);
        com.example.hms.model.Hospital elsewhere = new com.example.hms.model.Hospital();
        elsewhere.setId(UUID.randomUUID());
        com.example.hms.model.Appointment appointment = new com.example.hms.model.Appointment();
        appointment.setId(UUID.randomUUID());
        appointment.setHospital(here);
        appointment.setAppointmentDate(java.time.LocalDate.of(2026, 11, 20));
        appointment.setStartTime(java.time.LocalTime.of(10, 30));
        com.example.hms.model.BillingInvoice ours = new com.example.hms.model.BillingInvoice();
        ours.setId(UUID.randomUUID());
        ours.setHospital(here);
        ours.setInvoiceNumber("CHA-2026-000057");
        com.example.hms.model.BillingInvoice theirs = new com.example.hms.model.BillingInvoice();
        theirs.setId(UUID.randomUUID());
        theirs.setHospital(elsewhere);
        theirs.setInvoiceNumber("OTHER-1");
        PatientNotificationDelivery booking = skipped(PatientNotificationEvent.APPOINTMENT_CONFIRMATION, appointment.getId());
        PatientNotificationDelivery invoice = skipped(PatientNotificationEvent.INVOICE, ours.getId());
        PatientNotificationDelivery foreign = skipped(PatientNotificationEvent.INVOICE, theirs.getId());
        when(repository.searchForHospital(eq(hospitalId), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(booking, invoice, foreign), PageRequest.of(0, 25), 3));
        when(repository.countByStatusForHospital(hospitalId)).thenReturn(List.of());
        when(patientRepository.findAllById(any())).thenReturn(List.of(patient));
        when(appointmentRepository.findAllById(any())).thenReturn(List.of(appointment));
        when(invoiceRepository.findAllById(any())).thenReturn(List.of(ours, theirs));

        List<com.example.hms.payload.dto.notification.PatientNotificationDeliveryDTO> rows =
            service.search("SKIPPED", 0, 25).getContent();

        assertThat(rows.get(0).getSubjectDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 20));
        assertThat(rows.get(0).getSubjectTime()).isEqualTo(java.time.LocalTime.of(10, 30));
        assertThat(rows.get(1).getSubjectReference()).isEqualTo("CHA-2026-000057");
        assertThat(rows.get(2).getSubjectReference()).isNull();
        assertThat(rows).allSatisfy(row -> assertThat(row.getCreatedAt()).isNotNull());
    }

    private PatientNotificationDelivery skipped(PatientNotificationEvent event, UUID subjectId) {
        PatientNotificationDelivery row = PatientNotificationDelivery.builder().hospitalId(hospitalId)
            .patientId(patient.getId()).eventType(event).channel(NotificationChannel.EMAIL).subjectId(subjectId)
            .status(NotificationDeliveryStatus.SKIPPED).errorCode("OPTED_OUT").build();
        row.setId(UUID.randomUUID());
        row.setCreatedAt(java.time.LocalDateTime.of(2026, 11, 19, 9, 0));
        return row;
    }

    @Test
    @DisplayName("ALL lists every status; an unknown status is a 400")
    void statusFilter() {
        assertThat(PatientNotificationDeliveryAdminService.parseStatuses("all"))
            .containsExactlyInAnyOrder(NotificationDeliveryStatus.values());
        assertThat(PatientNotificationDeliveryAdminService.parseStatuses("retrying"))
            .containsExactly(NotificationDeliveryStatus.RETRYING);
        assertThatThrownBy(() -> PatientNotificationDeliveryAdminService.parseStatuses("DROP"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a FAILED send of this hospital is requeued, recording who")
    void retriesAFailedSend() {
        when(repository.findByIdAndHospitalId(failed.getId(), hospitalId)).thenReturn(Optional.of(failed));
        when(repository.requeueFailed(eq(failed.getId()), eq(hospitalId), eq(NotificationDeliveryStatus.FAILED),
            eq(NotificationDeliveryStatus.PENDING), any(), eq(adminId))).thenReturn(1);

        service.retry(failed.getId());

        verify(repository).requeueFailed(eq(failed.getId()), eq(hospitalId), eq(NotificationDeliveryStatus.FAILED),
            eq(NotificationDeliveryStatus.PENDING), any(), eq(adminId));
    }

    @Test
    @DisplayName("another hospital's send answers exactly like a missing one")
    void otherHospitalIsNotFound() {
        when(repository.findByIdAndHospitalId(failed.getId(), hospitalId)).thenReturn(Optional.empty());

        UUID id = failed.getId();
        assertThatThrownBy(() -> service.retry(id)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).requeueFailed(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a SENT send is never requeued: a manual retry cannot text the patient twice")
    void sentIsNotRetryable() {
        failed.setStatus(NotificationDeliveryStatus.SENT);
        when(repository.findByIdAndHospitalId(failed.getId(), hospitalId)).thenReturn(Optional.of(failed));

        UUID id = failed.getId();
        assertThatThrownBy(() -> service.retry(id)).isInstanceOf(BusinessException.class);
        verify(repository, never()).requeueFailed(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a concurrent requeue wins once; the second click is refused")
    void doubleClick() {
        when(repository.findByIdAndHospitalId(failed.getId(), hospitalId)).thenReturn(Optional.of(failed));
        when(repository.requeueFailed(any(), any(), any(), any(), any(), any())).thenReturn(0);

        UUID id = failed.getId();
        assertThatThrownBy(() -> service.retry(id)).isInstanceOf(BusinessException.class);
    }
}
