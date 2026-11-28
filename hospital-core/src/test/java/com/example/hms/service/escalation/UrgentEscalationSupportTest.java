package com.example.hms.service.escalation;

import com.example.hms.model.Hospital;
import com.example.hms.model.UrgentAlertIncident;
import com.example.hms.model.User;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UrgentAlertIncidentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.lab.CriticalResultEscalationLadder.Step;
import com.example.hms.service.lab.CriticalResultRecipients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scorecard K4: what every urgent alert shares on the one ladder: the steps come from the hospital's own minutes
 * and never stop, the seniors of a step are clinicians, the incident opens once, and an administrator learns that
 * an incident is open and nothing about the alert.
 */
@DisplayName("The one escalation ladder of urgent clinical alerts (K4)")
class UrgentEscalationSupportTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 26, 8, 0);

    private final CriticalResultRecipients recipients = mock(CriticalResultRecipients.class);
    private final HospitalRepository hospitals = mock(HospitalRepository.class);
    private final UrgentAlertIncidentRepository incidents = mock(UrgentAlertIncidentRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MessageSource messages = com.example.hms.i18n.TestMessageSources.bundles();
    private final com.example.hms.service.CriticalValueNotificationService criticalPages =
        mock(com.example.hms.service.CriticalValueNotificationService.class);
    private final UrgentEscalationSupport ladder =
        new UrgentEscalationSupport(recipients, hospitals, incidents, notifications, users, messages, criticalPages);
    private final Hospital hospital = new Hospital();

    @BeforeEach
    void setUp() {
        hospital.setId(HOSPITAL);
        when(hospitals.findById(HOSPITAL)).thenReturn(Optional.of(hospital));
        when(recipients.cover(HOSPITAL, "dr_order")).thenReturn(new LinkedHashSet<>(List.of("dr_cover")));
        when(recipients.medicalLead(hospital)).thenReturn(new LinkedHashSet<>(List.of("dr_chef")));
        when(recipients.administrators(HOSPITAL)).thenReturn(new LinkedHashSet<>(List.of("admin_a", "admin_b")));
    }

    @Test
    void theStepsAreThoseOfTheCriticalResultLadderAndNeverStop() {
        assertThat(ladder.due(0, T0, null, HOSPITAL, T0.plusMinutes(14))).isEmpty();
        assertThat(ladder.due(0, T0, null, HOSPITAL, T0.plusMinutes(15))).contains(Step.COVER);
        assertThat(ladder.due(1, T0, T0.plusMinutes(15), HOSPITAL, T0.plusMinutes(30))).contains(Step.MEDICAL_LEAD);
        assertThat(ladder.due(2, T0, T0.plusMinutes(30), HOSPITAL, T0.plusMinutes(60))).contains(Step.UNRESOLVED);
        // No cap: the hundredth step is a reminder like the fourth, a day after the one before it.
        assertThat(ladder.due(3, T0, T0.plusMinutes(60), HOSPITAL, T0.plusMinutes(90))).contains(Step.REMINDER);
        assertThat(ladder.due(100, T0, T0.plusDays(30), HOSPITAL, T0.plusDays(31))).contains(Step.REMINDER);
        assertThat(ladder.due(100, T0, T0.plusDays(30), HOSPITAL, T0.plusDays(30).plusHours(23))).isEmpty();
    }

    @Test
    void aHospitalsOwnMinutesMoveTheSteps() {
        hospital.setCriticalCoverAfterMinutes(10);
        hospital.setCriticalLeadAfterMinutes(20);
        hospital.setCriticalUnresolvedAfterMinutes(40);
        assertThat(ladder.due(0, T0, null, HOSPITAL, T0.plusMinutes(10))).contains(Step.COVER);
        assertThat(ladder.due(2, T0, T0.plusMinutes(20), HOSPITAL, T0.plusMinutes(40))).contains(Step.UNRESOLVED);
    }

    @Test
    void theSeniorsOfAStepAreTheCoverThenTheMedecinChefAndNeverAnAdministrator() {
        assertThat(ladder.seniors(HOSPITAL, Step.COVER, "dr_order")).containsExactly("dr_cover");
        for (Step step : List.of(Step.MEDICAL_LEAD, Step.UNRESOLVED, Step.REMINDER)) {
            assertThat(ladder.seniors(HOSPITAL, step, "dr_order")).containsExactly("dr_cover", "dr_chef");
        }
        assertThat(ladder.addedAt(HOSPITAL, Step.COVER, "dr_order")).containsExactly("dr_cover");
        assertThat(ladder.addedAt(HOSPITAL, Step.MEDICAL_LEAD, "dr_order")).containsExactly("dr_chef");
        assertThat(ladder.addedAt(HOSPITAL, Step.REMINDER, "dr_order")).isEmpty();
        verify(recipients, never()).administrators(any());
    }

    @Test
    void anIncidentOfAPatientAlreadyFlaggedIsRecordedWithoutASecondFlag() {
        // QA D4102: the register holds every incident; the administrators get one flag per patient.
        UUID alert = UUID.randomUUID();
        when(incidents.findBySourceTypeAndSourceIdAndResolvedAtIsNull(UrgentAlertIncident.SOURCE_URGENT_ALERT, alert))
            .thenReturn(Optional.empty());

        assertThat(ladder.openIncident(UrgentAlertIncident.SOURCE_URGENT_ALERT, alert, HOSPITAL, T0.plusMinutes(61),
            61, 3, false)).isTrue();

        verify(incidents).save(any());
        verify(notifications, never()).createNotification(any(), any(), anyString(), anyString());
    }

    @Test
    void theIncidentOpensOnceAndTheAdministratorsFlagCarriesMinutesOnly() {
        UUID alert = UUID.randomUUID();
        when(incidents.findBySourceTypeAndSourceIdAndResolvedAtIsNull(UrgentAlertIncident.SOURCE_IMAGING_REPORT, alert))
            .thenReturn(Optional.empty());

        assertThat(ladder.openIncident(UrgentAlertIncident.SOURCE_IMAGING_REPORT, alert, HOSPITAL, T0.plusMinutes(61),
            61, 3)).isTrue();

        ArgumentCaptor<UrgentAlertIncident> saved = ArgumentCaptor.forClass(UrgentAlertIncident.class);
        verify(incidents).save(saved.capture());
        assertThat(saved.getValue().getSourceId()).isEqualTo(alert);
        assertThat(saved.getValue().getMinutesUnacknowledged()).isEqualTo(61);
        assertThat(saved.getValue().getRecipientsTold()).isEqualTo(3);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(notifications, times(2)).createNotification(eq(UrgentEscalationSupport.ADMIN_FLAG_KEY), args.capture(),
            anyString(), eq(UrgentEscalationSupport.ADMIN_FLAG_TYPE));
        // The flag's one argument is the number of minutes: no patient, no study, no value.
        assertThat(args.getAllValues()).allSatisfy(a -> assertThat(a).containsExactly(61L));

        // A second step finds the incident open: no second row, no second flag.
        when(incidents.findBySourceTypeAndSourceIdAndResolvedAtIsNull(UrgentAlertIncident.SOURCE_IMAGING_REPORT, alert))
            .thenReturn(Optional.of(saved.getValue()));
        assertThat(ladder.openIncident(UrgentAlertIncident.SOURCE_IMAGING_REPORT, alert, HOSPITAL, T0.plusMinutes(91),
            91, 3)).isFalse();
        verify(incidents, times(1)).save(any());
        verify(notifications, times(2)).createNotification(any(), any(), anyString(), anyString());
    }

    @Test
    void theAdministratorsFlagHasOneArgumentInEveryLanguage() {
        for (Locale locale : List.of(Locale.ENGLISH, Locale.FRENCH, Locale.forLanguageTag("es"))) {
            String text = messages.getMessage(UrgentEscalationSupport.ADMIN_FLAG_KEY, null, locale);
            assertThat(text).as("the administrators' flag in " + locale).contains("{0}").doesNotContain("{1}");
        }
    }

    @Test
    void anAcknowledgementClosesTheIncidentAndSaysHowItEnded() {
        UUID alert = UUID.randomUUID();
        UUID clinician = UUID.randomUUID();
        UrgentAlertIncident open = UrgentAlertIncident.builder().sourceType(UrgentAlertIncident.SOURCE_URGENT_ALERT)
            .sourceId(alert).hospitalId(HOSPITAL).openedAt(T0).build();
        when(incidents.findBySourceTypeAndSourceIdAndResolvedAtIsNull(UrgentAlertIncident.SOURCE_URGENT_ALERT, alert))
            .thenReturn(Optional.of(open));

        ladder.closeIncident(UrgentAlertIncident.SOURCE_URGENT_ALERT, alert, UrgentAlertIncident.RESOLVED_ACKNOWLEDGED,
            clinician, T0.plusHours(2));

        verify(incidents).saveAndFlush(open);
        assertThat(open.getResolvedAt()).isEqualTo(T0.plusHours(2));
        assertThat(open.getResolution()).isEqualTo(UrgentAlertIncident.RESOLVED_ACKNOWLEDGED);
        assertThat(open.getResolvedByUserId()).isEqualTo(clinician);
    }

    @Test
    void theStaffSmsNamesThePatientByInitialsAndNothingElse() {
        User cover = new User();
        cover.setUsername("dr_cover");
        cover.setPhoneNumber("+22670000001");
        when(users.findByUsernameInIgnoreCase(anyList())).thenReturn(List.of(cover));

        UUID alert = UUID.randomUUID();
        ladder.smsAfterCommit(Set.of("dr_cover"), "AO", alert);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(criticalPages).sendCriticalSms(eq(alert), eq("+22670000001"), text.capture());
        // The whole text: the patient's initials, and that something waits. No alert, no value, no name.
        assertThat(text.getValue())
            .isEqualTo("e-Keneya : une alerte urgente du patient AO attend un accusé de réception. Ouvrez e-Keneya.");
    }

    @Test
    void aStepThatAddsNobodyTextsNobody() {
        ladder.smsAfterCommit(Set.of(), "AO", UUID.randomUUID());
        ladder.smsAfterCommit(Set.of("dr_cover"), "AO", UUID.randomUUID());
        // Nobody in the first; nobody with a number in the second.
        verify(criticalPages, never()).sendCriticalSms(any(), any(), any());
    }
}
