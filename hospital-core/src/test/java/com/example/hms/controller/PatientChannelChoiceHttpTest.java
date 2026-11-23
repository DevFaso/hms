package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import com.example.hms.service.notification.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real controller, preference writer, consent and message gate; transport and persistence are mocked. */
class PatientChannelChoiceHttpTest {
    @Test
    void everyOfferedStaffSwitchHasAServerEffectAndNoneSendsOnEitherChannel() throws Exception {
        var patients = mock(PatientRepository.class);
        var registrations = mock(PatientHospitalRegistrationRepository.class);
        var preferences = mock(NotificationPreferenceRepository.class);
        var users = mock(UserRepository.class);
        var auth = mock(ControllerAuthUtils.class);
        var sms = mock(SmsService.class);
        var email = mock(EmailService.class);
        when(sms.deliversRealSms()).thenReturn(true);
        var account = new User(); account.setId(UUID.randomUUID());
        var patient = new Patient(); patient.setId(UUID.randomUUID()); patient.setUser(account);
        patient.setPhoneNumberPrimary("+22670000000"); patient.setEmail("cdx-contact@example.invalid");
        var hospital = UUID.randomUUID(); var recorder = UUID.randomUUID();
        var stored = new ArrayList<NotificationPreference>();
        when(patients.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(registrations.existsByPatientIdAndHospitalId(patient.getId(), hospital)).thenReturn(true);
        when(users.lockForNotificationPreferences(account.getId())).thenReturn(Optional.of(account));
        when(auth.resolveUserId(any())).thenReturn(Optional.of(recorder));
        when(auth.resolveHospitalScope(any(), any(), any(), eq(true))).thenReturn(hospital);
        when(preferences.findByUser_Id(account.getId())).thenReturn(stored);
        when(preferences.findByUser_IdAndNotificationType(eq(account.getId()), any()))
            .thenAnswer(call -> stored.stream().filter(row -> row.getNotificationType() == call.getArgument(1)).toList());
        when(preferences.saveAll(any())).thenAnswer(call -> {
            Iterable<NotificationPreference> rows = call.getArgument(0);
            rows.forEach(row -> {
                if (row.getId() == null) row.setId(UUID.randomUUID());
                if (!stored.contains(row)) stored.add(row);
            });
            return stored;
        });
        var writer = new PatientChannelPreferenceService(patients, registrations, preferences, users,
            mock(AuditEventLogService.class));
        var mvc = MockMvcBuilders.standaloneSetup(new PatientChannelPreferenceController(writer, auth)).build();
        var principal = new UsernamePasswordAuthenticationToken("cdx-desk", null);
        var consent = new PatientChannelConsent(preferences);
        var gate = new PatientMessageGate(consent, mock(PatientNotificationLedger.class), sms, email);
        for (var type : PatientChannelPreferenceService.DESK_TYPES) {
            for (var channel : PatientChannelPreferenceService.DESK_CHANNELS) {
                String body = "[{\"notificationType\":\"" + type + "\",\"channel\":\"" + channel + "\",\"enabled\":false}]";
                var response = mvc.perform(put("/patients/{id}/channel-preferences", patient.getId())
                    .principal(principal).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(response).contains(recorder.toString());
                assertThat(consent.allows(account.getId(), type, channel)).isFalse();
            }
        }
        // Every announced event goes through the same saved preference, including queued families.
        for (var event : PatientNotificationEvent.values()) {
            var notice = new PatientMessageGate.Notice(event, hospital, UUID.randomUUID());
            assertThat(gate.smsNow(patient, notice, "cdx test message")).isFalse();
            assertThat(gate.htmlEmail(patient, notice, patient.getEmail(), "cdx", "cdx test")).isFalse();
        }
        verify(sms, atLeastOnce()).deliversRealSms();
        verify(sms, never()).send(anyString(), anyString());
        verifyNoInteractions(email);
        assertThat(stored).hasSize(PatientChannelPreferenceService.DESK_TYPES.size() * 2);
    }
}
