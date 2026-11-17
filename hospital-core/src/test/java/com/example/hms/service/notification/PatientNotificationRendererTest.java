package com.example.hms.service.notification;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.model.User;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientNotificationDeliveryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UltrasoundReportRepository;
import com.example.hms.service.i18n.PatientLocaleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Patient notification renderer: still right to send, no clinical content, patient's language")
class PatientNotificationRendererTest {

    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");

    @Mock private PatientNotificationDeliveryRepository repository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private ImagingReportRepository imagingReportRepository;
    @Mock private UltrasoundReportRepository ultrasoundReportRepository;
    @Mock private PatientChannelConsent consent;
    @Mock private PatientLocaleResolver localeResolver;

    private PatientNotificationRenderer renderer;
    private Patient patient;
    private Hospital hospital;
    private LabOrder order;
    private LabResult released;

    @BeforeEach
    void setUp() {
        renderer = new PatientNotificationRenderer(repository, patientRepository, hospitalRepository,
            appointmentRepository, labOrderRepository, labResultRepository, imagingReportRepository,
            ultrasoundReportRepository, consent, localeResolver, TestMessageSources.bundles(),
            Clock.fixed(NOW, ZoneOffset.UTC));
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setPhoneNumberPrimary("+22670707070");
        patient.setEmail("awa@example.com");
        User user = new User();
        user.setId(UUID.randomUUID());
        patient.setUser(user);
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("CMA Pissy");
        order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setStatus(LabOrderStatus.COMPLETED);
        released = new LabResult();
        released.setReleased(true);
        released.setResultValue("7.2 mmol/L HIV positive");

        lenient().when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        lenient().when(hospitalRepository.findById(hospital.getId())).thenReturn(Optional.of(hospital));
        lenient().when(consent.allows(any(), any(), any())).thenReturn(true);
        lenient().when(localeResolver.resolve(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        lenient().when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of(released));
    }

    private PatientNotificationDelivery row(PatientNotificationEvent event, UUID subjectId, NotificationChannel channel) {
        PatientNotificationDelivery row = PatientNotificationDelivery.builder()
            .hospitalId(hospital.getId()).patientId(patient.getId()).eventType(event).subjectId(subjectId)
            .channel(channel).status(NotificationDeliveryStatus.PENDING).attempts(1)
            .nextAttemptAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)).build();
        row.setId(UUID.randomUUID());
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    @DisplayName("a result SMS is French by default and says only that a result is available")
    void resultSmsCarriesNoClinicalContent() {
        PatientNotificationDelivery row = row(PatientNotificationEvent.LAB_RESULT_AVAILABLE, order.getId(),
            NotificationChannel.SMS);

        PatientNotificationRenderer.Prepared prepared = renderer.prepare(row.getId());

        assertThat(prepared.recipient()).isEqualTo("+22670707070");
        assertThat(prepared.body())
            .startsWith("CMA Pissy : un nouveau résultat est disponible.")
            .contains("e-Keneya")
            .doesNotContain("7.2", "mmol", "HIV", "positive");
        assertThat(prepared.toString()).doesNotContain("7070").doesNotContain("résultat");
    }

    @Test
    @DisplayName("the patient's own language is used")
    void patientLanguage() {
        when(localeResolver.resolve(any(), any())).thenReturn(Locale.ENGLISH);
        PatientNotificationDelivery row = row(PatientNotificationEvent.LAB_RESULT_AVAILABLE, order.getId(),
            NotificationChannel.SMS);

        assertThat(renderer.prepare(row.getId()).body()).startsWith("CMA Pissy: a new result is available.");
    }

    @Test
    @DisplayName("a voided lab order is not announced")
    void voidedOrderIsSkipped() {
        order.setStatus(LabOrderStatus.ENTERED_IN_ERROR);
        PatientNotificationDelivery row = row(PatientNotificationEvent.LAB_RESULT_AVAILABLE, order.getId(),
            NotificationChannel.SMS);

        assertThat(renderer.prepare(row.getId())).isNull();
        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.SKIPPED);
        assertThat(row.getErrorCode()).isEqualTo("SUBJECT_WITHDRAWN");
    }

    @Test
    @DisplayName("a channel turned off since queueing is skipped")
    void optedOutSinceQueueing() {
        when(consent.allows(any(), any(), any())).thenReturn(false);
        PatientNotificationDelivery row = row(PatientNotificationEvent.LAB_RESULT_AVAILABLE, order.getId(),
            NotificationChannel.SMS);

        assertThat(renderer.prepare(row.getId())).isNull();
        assertThat(row.getErrorCode()).isEqualTo("OPTED_OUT");
    }

    @Test
    @DisplayName("a patient who has died is never texted")
    void deceasedPatientIsSkipped() {
        patient.setDeceasedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(1));
        PatientNotificationDelivery row = row(PatientNotificationEvent.LAB_RESULT_AVAILABLE, order.getId(),
            NotificationChannel.SMS);

        assertThat(renderer.prepare(row.getId())).isNull();
        assertThat(row.getErrorCode()).isEqualTo("PATIENT_DECEASED");
    }

    @Test
    @DisplayName("a number removed since queueing is skipped")
    void numberRemoved() {
        patient.setPhoneNumberPrimary(null);
        PatientNotificationDelivery row = row(PatientNotificationEvent.LAB_RESULT_AVAILABLE, order.getId(),
            NotificationChannel.SMS);

        assertThat(renderer.prepare(row.getId())).isNull();
        assertThat(row.getErrorCode()).isEqualTo("NO_CONTACT");
    }

    @Test
    @DisplayName("an appointment confirmation names the day, the time and the hospital; a cancelled one is skipped")
    void appointmentConfirmation() {
        Appointment appointment = new Appointment();
        appointment.setId(UUID.randomUUID());
        appointment.setAppointmentDate(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).toLocalDate().plusDays(3));
        appointment.setStartTime(java.time.LocalTime.of(9, 30));
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setReason("Suivi VIH");
        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));
        PatientNotificationDelivery row = row(PatientNotificationEvent.APPOINTMENT_CONFIRMATION, appointment.getId(),
            NotificationChannel.SMS);

        String body = renderer.prepare(row.getId()).body();
        assertThat(body).startsWith("Rendez-vous enregistré le").contains("09:30").contains("CMA Pissy")
            .doesNotContain("VIH");

        appointment.setStatus(AppointmentStatus.CANCELLED);
        row.setStatus(NotificationDeliveryStatus.PENDING);
        assertThat(renderer.prepare(row.getId())).isNull();
        assertThat(row.getErrorCode()).isEqualTo("SUBJECT_WITHDRAWN");
    }

    @Test
    @DisplayName("a reminder email escapes the hospital name into the HTML")
    void emailIsEscaped() {
        hospital.setName("Clinique <b>A&B</b>");
        PatientNotificationDelivery row = row(PatientNotificationEvent.LAB_RESULT_AVAILABLE, order.getId(),
            NotificationChannel.EMAIL);

        PatientNotificationRenderer.Prepared prepared = renderer.prepare(row.getId());

        assertThat(prepared.recipient()).isEqualTo("awa@example.com");
        assertThat(prepared.subject()).startsWith("Un nouveau résultat est disponible");
        assertThat(prepared.body()).contains("Clinique &lt;b&gt;A&amp;B&lt;/b&gt;").doesNotContain("<b>A");
    }

    @Test
    @DisplayName("a row no longer open is not prepared")
    void closedRow() {
        PatientNotificationDelivery row = row(PatientNotificationEvent.LAB_RESULT_AVAILABLE, order.getId(),
            NotificationChannel.SMS);
        row.setStatus(NotificationDeliveryStatus.SENT);

        assertThat(renderer.prepare(row.getId())).isNull();
        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
    }
}
