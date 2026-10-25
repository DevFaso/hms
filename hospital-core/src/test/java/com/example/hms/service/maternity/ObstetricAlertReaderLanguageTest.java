package com.example.hms.service.maternity;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.labor.ObstetricAlertEscalation;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.repository.ObstetricAlertEscalationRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.NotificationService;
import com.example.hms.service.i18n.NotificationLocalizer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D665 remainder: an obstetric alert notice translated only its frame; the
 * clinical sentence ("Hypoglycémie critique ...") stayed French for Spanish and
 * English readers. The first notice and the reminder now carry the sentence as
 * a key with its own arguments, rendered whole in the reader's language. Args
 * go through a JSON round trip, as they do in security.notifications.message_args.
 */
class ObstetricAlertReaderLanguageTest {

    private static final MessageSource MESSAGES = TestMessageSources.bundles();
    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final List<String> FRENCH = List.of("Hypoglycémie", "glycémie", "Pertes sanguines",
        "post-partum", "Alerte", "rappel", "nouveau-né");

    private final NotificationService notificationService = mock(NotificationService.class);
    private final ObstetricAlertEscalationRepository escalationRepository = mock(ObstetricAlertEscalationRepository.class);
    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PatientRepository patientRepository = mock(PatientRepository.class);
    private final OnCallScheduleRepository onCallScheduleRepository = mock(OnCallScheduleRepository.class);
    private final InBasketItemRepository inBasketItemRepository = mock(InBasketItemRepository.class);
    private final NotificationLocalizer localizer = new NotificationLocalizer(MESSAGES);
    private ObstetricAlertEscalationWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ObstetricAlertEscalationWorker(escalationRepository, inBasketItemRepository,
            onCallScheduleRepository, staffRepository, userRepository, patientRepository,
            notificationService, mock(InBasketService.class), MESSAGES,
            Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC), mock(ObstetricAlertCurrency.class));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(eq(HOSPITAL), any())).thenReturn(List.of("drA"));
        when(onCallScheduleRepository.findByHospitalAndWindow(eq(HOSPITAL), any(), any())).thenReturn(List.of());
        when(userRepository.findByUsernameInIgnoreCase(anyList())).thenReturn(List.<User>of());
        when(escalationRepository.save(any())).thenAnswer(inv -> {
            ObstetricAlertEscalation r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
    }

    /** What the reader sees: the stored key and args (after JSON) rendered in her locale. */
    private String read(String key, Object[] args, Locale locale) throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<Object> stored = json.readValue(json.writeValueAsString(NotificationLocalizer.storable(args)),
            new TypeReference<List<Object>>() { });
        return localizer.render(key, stored, locale);
    }

    private Object[] sentArgs(String key) {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(notificationService).createNotification(eq(key), args.capture(), eq("drA"), any());
        return args.getValue();
    }

    private static void assertNoFrench(String body) {
        FRENCH.forEach(word -> assertThat(body).doesNotContain(word));
    }

    @Test
    void theNewbornFirstNoticeReadsWholeInEachLanguage() throws Exception {
        worker.raise(new ObstetricAlertEscalationService.UrgentAlert(HOSPITAL, PATIENT, "Awa Traore",
            ObstetricAlertEscalationService.SOURCE_NEWBORN, UUID.randomUUID(), "GLUCOSE", "glycémie",
            "Hypoglycémie critique détectée. Démarrer immédiatement le protocole glucose.", null, "texte",
            "newborn.alert.msg.newborn-glucose", null));
        Object[] args = sentArgs("newborn.alert.notification");

        assertThat(read("newborn.alert.notification", args, Locale.FRENCH))
            .contains("glycémie").contains("Hypoglycémie critique détectée");
        String es = read("newborn.alert.notification", args, Locale.of("es"));
        String en = read("newborn.alert.notification", args, Locale.ENGLISH);
        assertThat(es).contains("glucemia").contains("Hipoglucemia crítica detectada");
        assertThat(en).contains("blood glucose").contains("Critical hypoglycaemia detected");
        assertNoFrench(es);
        assertNoFrench(en);
    }

    @Test
    void theLabourReminderReadsWholeInEachLanguageWithItsNumbers() throws Exception {
        ObstetricAlertEscalation row = ObstetricAlertEscalation.builder()
            .hospitalId(HOSPITAL).patientId(PATIENT)
            .sourceType(ObstetricAlertEscalationService.SOURCE_DELIVERY).alertType("HEMORRHAGE")
            .typeLabel("hémorragie")
            .alertMessage("Pertes sanguines estimées 1200 ml : seuil d'hémorragie du post-partum atteint.")
            .alertMessageKey("labor.alert.msg.labor-pph").alertMessageArgs(List.of(1200))
            .escalationLevel((short) 0).build();
        row.setId(UUID.randomUUID());
        when(escalationRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(escalationRepository.claimRound(eq(row.getId()), eq((short) 0), eq((short) 1), any())).thenReturn(1);
        Patient p = new Patient();
        p.setFirstName("Awa");
        p.setLastName("Traore");
        when(patientRepository.findByIdUnscoped(PATIENT)).thenReturn(Optional.of(p));

        assertThat(worker.escalateOne(row.getId())).isTrue();
        Object[] args = sentArgs(ObstetricAlertEscalationWorker.REMINDER_KEY);

        assertThat(read(ObstetricAlertEscalationWorker.REMINDER_KEY, args, Locale.FRENCH))
            .contains("rappel 1").contains("Pertes sanguines estimées 1200 ml");
        String es = read(ObstetricAlertEscalationWorker.REMINDER_KEY, args, Locale.of("es"));
        String en = read(ObstetricAlertEscalationWorker.REMINDER_KEY, args, Locale.ENGLISH);
        assertThat(es).contains("recordatorio 1").contains("Pérdida de sangre estimada 1200 ml");
        assertThat(en).contains("reminder 1").contains("Estimated blood loss 1200 ml");
        assertNoFrench(es);
        assertNoFrench(en);
    }

    @Test
    void aRowRaisedWithoutAKeyKeepsItsStoredSentence() {
        assertThat(ObstetricAlertEscalationWorker.alertMessageArg(null, null, "BP")).isEqualTo("BP");
        assertThat(ObstetricAlertEscalationWorker.alertMessageArg("k", List.of(1), "BP"))
            .isEqualTo(java.util.Map.of("key", "k", "args", List.of(1)));
    }
}
