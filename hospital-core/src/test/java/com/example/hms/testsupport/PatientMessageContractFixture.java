package com.example.hms.testsupport;

import com.example.hms.enums.*;
import com.example.hms.model.*;
import com.example.hms.repository.*;
import com.example.hms.service.*;
import com.example.hms.service.i18n.PatientLocaleResolver;
import com.example.hms.service.mail.MailOutboxService;
import com.example.hms.service.notification.*;
import com.example.hms.i18n.TestMessageSources;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.*;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real consent, verification, locale, ledger, gate and mail rendering; repositories and transports are local fakes. */
public final class PatientMessageContractFixture {
    public enum Refusal { NONE, OPT_OUT, UNVERIFIED, CONTACT_CHANGED }
    public record Wire(NotificationChannel channel, String recipient, String subject, String body) {}
    public final Patient patient = new Patient();
    public final Hospital hospital = new Hospital();
    public final UUID subjectId = UUID.randomUUID();
    public final MessageSource messages = TestMessageSources.bundles();
    public final Clock clock = Clock.fixed(Instant.parse("2026-11-24T08:00:00Z"), ZoneOffset.UTC);
    public final SmsService sms = mock(SmsService.class);
    public final JavaMailSender smtp = mock(JavaMailSender.class);
    public final MailOutboxService outbox = mock(MailOutboxService.class);
    public final EmailServiceImpl email = new EmailServiceImpl(smtp, messages, outbox);
    public final NotificationPreferenceRepository preferences = mock(NotificationPreferenceRepository.class);
    public final SocialHistoryRepository histories = mock(SocialHistoryRepository.class);
    public final PatientRepository patients = mock(PatientRepository.class);
    public final PatientNotificationDeliveryRepository deliveries = mock(PatientNotificationDeliveryRepository.class);
    public final PatientChannelConsent consent = new PatientChannelConsent(preferences);
    public final PatientLocaleResolver locales = new PatientLocaleResolver(histories);
    public final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    public final PatientNotificationLedger ledger;
    public final PatientMessageGate gate;
    public final List<Wire> wire = new ArrayList<>();
    public final Map<String,PatientNotificationDelivery> rows = new LinkedHashMap<>();
    public final Locale expectedLocale;

    public PatientMessageContractFixture(PatientNotificationEvent event, NotificationChannel channel, String language, Refusal refusal) {
        expectedLocale = language.equals("default") ? Locale.FRENCH : Locale.forLanguageTag(language);
        patient.setId(UUID.randomUUID()); patient.setFirstName("cdx Awa"); patient.setLastName("Ouédraogo");
        patient.setPhoneNumberPrimary("+22670000000"); patient.setPhoneNumberSecondary("+22670000001");
        patient.setEmail("cdx-patient@example.invalid");
        patient.setVerifiedSmsRecipient(patient.getPhoneNumberPrimary()); patient.setVerifiedEmailRecipient(patient.getEmail());
        User account = new User(); account.setId(UUID.randomUUID()); account.setUsername("cdx-contract"); patient.setUser(account);
        hospital.setId(UUID.randomUUID()); hospital.setName("cdx Hospital"); hospital.setEmail("cdx-hospital@example.invalid");
        hospital.setPhoneNumber("+22670000002");
        if (!language.equals("default")) {
            PatientSocialHistory history = new PatientSocialHistory(); history.setPreferredLanguage(language);
            when(histories.findFirstByPatient_IdAndActiveTrueOrderByRecordedDateDesc(patient.getId())).thenReturn(Optional.of(history));
        }
        when(patients.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(patients.findNotificationAccountScoped(patient.getId(),hospital.getId())).thenReturn(Optional.of(account.getId()));
        when(preferences.findByUser_IdAndNotificationType(account.getId(),event.preferenceType())).thenAnswer(call -> {
            if (refusal != Refusal.OPT_OUT) return List.of();
            NotificationPreference preference = NotificationPreference.builder().channel(channel).build();
            preference.recordChoice(false, UUID.randomUUID(), LocalDateTime.now(clock));
            return List.of(preference);
        });
        if (refusal == Refusal.UNVERIFIED) { patient.setVerifiedSmsRecipient(null); patient.setVerifiedEmailRecipient(null); }
        if (refusal == Refusal.CONTACT_CHANGED) { patient.setPhoneNumberPrimary("+22670000003"); patient.setEmail("cdx-changed@example.invalid"); }
        when(sms.deliversRealSms()).thenReturn(true);
        doAnswer(call -> {wire.add(new Wire(NotificationChannel.SMS,call.getArgument(0),null,call.getArgument(1)));return null;})
            .when(sms).send(anyString(),anyString());
        ReflectionTestUtils.setField(email,"configuredMailHost","smtp.example.invalid");
        ReflectionTestUtils.setField(email,"smtpAuthProperty","false");
        ReflectionTestUtils.setField(email,"frontendBaseUrl","https://example.invalid");
        when(outbox.enqueue(anyList(),anyList(),anyList(),anyString(),anyString())).thenAnswer(call -> {
            List<String> recipients=call.getArgument(0);
            for(String recipient:recipients)wire.add(new Wire(NotificationChannel.EMAIL,recipient,call.getArgument(3),call.getArgument(4)));
            return UUID.randomUUID();
        });
        doAnswer(call -> {
            MimeMessage message=new MimeMessage(Session.getInstance(new Properties()));
            call.<MimeMessagePreparator>getArgument(0).prepare(message);
            for(var recipient:message.getAllRecipients())wire.add(new Wire(NotificationChannel.EMAIL,recipient.toString(),message.getSubject(),message.getContent().toString()));
            return null;
        }).when(smtp).send(any(MimeMessagePreparator.class));
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(deliveries.existsByIdempotencyKey(anyString())).thenAnswer(call -> rows.containsKey(call.getArgument(0)));
        when(deliveries.saveAndFlush(any())).thenAnswer(call -> {
            PatientNotificationDelivery row=call.getArgument(0);row.setId(UUID.randomUUID());rows.put(row.getIdempotencyKey(),row);return row;
        });
        when(deliveries.findById(any())).thenAnswer(call -> rows.values().stream().filter(row -> row.getId().equals(call.getArgument(0))).findFirst());
        ledger=new PatientNotificationLedger(deliveries,patients,consent,sms,email,clock,transactions);
        gate=new PatientMessageGate(consent,ledger,sms,email,new PatientRecipientVerification(),new PatientRecipientCurrentCheck(patients,new PatientRecipientVerification()));
    }

    public <T> T realSender(Class<T> type, Map<String,Object> dependencies) {
        T sender=mock(type,CALLS_REAL_METHODS);
        dependencies.forEach((name,value)->ReflectionTestUtils.setField(sender,name,value));
        return sender;
    }
    public static Set<PatientNotificationEvent> coveredEvents() {
        return EnumSet.of(PatientNotificationEvent.APPOINTMENT_CONFIRMATION,PatientNotificationEvent.APPOINTMENT_REMINDER,
            PatientNotificationEvent.LAB_RESULT_AVAILABLE,PatientNotificationEvent.IMAGING_RESULT_AVAILABLE,PatientNotificationEvent.ULTRASOUND_RESULT_AVAILABLE,
            PatientNotificationEvent.APPOINTMENT_RESCHEDULED,PatientNotificationEvent.APPOINTMENT_CANCELLED,PatientNotificationEvent.APPOINTMENT_COMPLETED,
            PatientNotificationEvent.APPOINTMENT_NO_SHOW,PatientNotificationEvent.WAITLIST_OFFER,PatientNotificationEvent.RECALL_DUE,
            PatientNotificationEvent.VISIT_SUMMARY,PatientNotificationEvent.PHARMACY_DISPENSED,PatientNotificationEvent.PHARMACY_READY,
            PatientNotificationEvent.PHARMACY_READY_CANCELLED,PatientNotificationEvent.PHARMACY_READY_REMINDER,PatientNotificationEvent.PHARMACY_OUT_OF_STOCK,
            PatientNotificationEvent.PHARMACY_REFILL_REMINDER,PatientNotificationEvent.INVOICE);
    }
}
