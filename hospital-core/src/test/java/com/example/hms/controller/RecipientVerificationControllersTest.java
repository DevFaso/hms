package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.service.PatientPortalService;
import com.example.hms.service.notification.PatientRecipientChallengeService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecipientVerificationControllersTest {
    final PatientRecipientChallengeService service = mock(PatientRecipientChallengeService.class);
    final ControllerAuthUtils authUtils = mock(ControllerAuthUtils.class);
    final PatientHospitalRegistrationRepository registrations = mock(PatientHospitalRegistrationRepository.class);
    final PatientPortalService portal = mock(PatientPortalService.class);
    final Authentication auth = mock(Authentication.class);
    final UUID patientId = UUID.randomUUID(), hospitalId = UUID.randomUUID(), actor = UUID.randomUUID();

    @Test
    void staffEndpointsRequireTheRegisteredHospitalAndAttributeTheActor() {
        var controller = new PatientRecipientVerificationController(service, registrations, authUtils);
        when(authUtils.resolveHospitalScope(auth, hospitalId, null, true)).thenReturn(hospitalId);
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(actor));
        when(registrations.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        var view = new PatientRecipientChallengeService.View(NotificationChannel.SMS, false, null);
        when(service.status(patientId)).thenReturn(List.of(view));
        assertThat(controller.status(patientId, hospitalId, auth)).containsExactly(view);
        controller.request(patientId, NotificationChannel.SMS, hospitalId, auth);
        controller.confirm(patientId, NotificationChannel.SMS, hospitalId,
            new PatientRecipientVerificationController.Confirmation("123456"), auth);
        verify(service).request(eq(patientId), eq(NotificationChannel.SMS), eq(actor), any());
        verify(service).confirm(patientId, NotificationChannel.SMS, actor, "123456");
        verify(authUtils, times(3)).requireAuth(auth);
    }

    @Test
    void unknownHospitalOrForeignPatientNeverReachesTheChallengeService() {
        var controller = new PatientRecipientVerificationController(service, registrations, authUtils);
        assertThatThrownBy(() -> controller.status(patientId, null, auth)).isInstanceOf(ResourceNotFoundException.class);
        when(authUtils.resolveHospitalScope(auth, hospitalId, null, true)).thenReturn(hospitalId);
        assertThatThrownBy(() -> controller.request(patientId, NotificationChannel.EMAIL, hospitalId, auth))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(service);
    }

    @Test
    void selfEndpointsUseOnlyThePatientLinkedToTheAuthenticatedAccount() {
        var controller = new PatientSelfRecipientVerificationController(service, portal, authUtils);
        when(portal.resolvePatientId(auth)).thenReturn(patientId);
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(actor));
        var view = new PatientRecipientChallengeService.View(NotificationChannel.EMAIL, true, null);
        when(service.status(patientId)).thenReturn(List.of(view));
        assertThat(controller.status(auth)).containsExactly(view);
        controller.request(NotificationChannel.EMAIL, auth);
        controller.confirm(NotificationChannel.EMAIL, new PatientRecipientVerificationController.Confirmation("654321"), auth);
        verify(service).request(eq(patientId), eq(NotificationChannel.EMAIL), eq(actor), any());
        verify(service).confirm(patientId, NotificationChannel.EMAIL, actor, "654321");
        verify(portal, times(3)).resolvePatientId(auth);
    }

    @Test
    void missingActorCannotRequestOrConfirmEvenWithALinkedPatient() {
        var controller = new PatientSelfRecipientVerificationController(service, portal, authUtils);
        when(portal.resolvePatientId(auth)).thenReturn(patientId);
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.request(NotificationChannel.SMS, auth)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.confirm(NotificationChannel.SMS,
            new PatientRecipientVerificationController.Confirmation("123456"), auth)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(service);
    }
}
