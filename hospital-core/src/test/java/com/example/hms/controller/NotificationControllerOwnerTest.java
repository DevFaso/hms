package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.FacilityType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Notification;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.provider.ProviderCallerResolver;
import com.example.hms.service.NotificationService;
import com.example.hms.service.NotificationService.ReadOutcome;
import com.example.hms.service.NotificationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PUT /notifications/{id}/read marks the caller's OWN notification only. It
 * used to mark any notification by id. Someone else's notification answers
 * exactly as an unknown id: the same 404 exception and message. A broadcast's
 * one read flag is set by unconfined staff only, decided on the caller's live
 * context (not the request's holder).
 */
class NotificationControllerOwnerTest {

    private final NotificationService service = mock(NotificationService.class);
    private final NotificationController controller =
        new NotificationController(service, mock(ControllerAuthUtils.class));
    private final Principal nurse = new UsernamePasswordAuthenticationToken("nurse1", null, List.of());

    @Test
    @DisplayName("the caller's own notification is marked, through the owner-checked service call")
    void ownNotificationIsMarked() {
        UUID id = UUID.randomUUID();
        when(service.markAsRead(id, nurse, true)).thenReturn(ReadOutcome.MARKED);

        assertThat(controller.markAsRead(id, nurse).getStatusCode().value()).isEqualTo(200);
        verify(service).markAsRead(id, nurse, true);
    }

    @Test
    @DisplayName("someone else's notification answers exactly as an unknown id")
    void foreignNotificationIsAMiss() {
        UUID foreign = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(service.markAsRead(any(), any(), anyBoolean())).thenReturn(ReadOutcome.NOT_FOUND);

        Throwable asForeign = catchThrowable(() -> controller.markAsRead(foreign, nurse));
        Throwable asUnknown = catchThrowable(() -> controller.markAsRead(unknown, nurse));

        assertThat(asForeign).isInstanceOf(ResourceNotFoundException.class);
        assertThat(asUnknown).isInstanceOf(ResourceNotFoundException.class);
        assertThat(asForeign.getMessage().replace(foreign.toString(), "<id>"))
            .isEqualTo(asUnknown.getMessage().replace(unknown.toString(), "<id>"));
    }

    @Test
    @DisplayName("a broadcast's one read flag: a doctor sets it with NO context in the holder (the filter fallback); a provider user and a patient get the foreign-id 404")
    @SuppressWarnings("unchecked")
    void broadcastIsMarkedByUnconfinedStaffOnly() {
        UUID id = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        NotificationRepository repository = mock(NotificationRepository.class);
        Notification broadcast = Notification.builder().id(id).message("announcement").read(false).build();
        Notification someoneElses = Notification.builder().id(foreign).message("x").recipientUsername("other")
            .read(false).build();
        when(repository.findById(id)).thenReturn(Optional.of(broadcast));
        when(repository.findById(foreign)).thenReturn(Optional.of(someoneElses));
        ProviderCallerResolver callerResolver = mock(ProviderCallerResolver.class);
        ObjectProvider<ProviderCallerResolver> callerResolverProvider = mock(ObjectProvider.class);
        when(callerResolverProvider.getIfAvailable()).thenReturn(callerResolver);
        NotificationController withRealService = new NotificationController(
            new NotificationServiceImpl(repository, mock(NotificationWebSocketController.class),
                mock(NotificationPreferenceRepository.class), mock(UserRepository.class), callerResolverProvider),
            mock(ControllerAuthUtils.class));
        HospitalContextHolder.clear();

        for (HospitalContext refused : List.of(
                HospitalContext.builder().principalUserId(UUID.randomUUID())
                    .assignedRoles(Set.of("ROLE_PHARMACIST"))
                    .providerFacilityTypes(Set.of(FacilityType.PHARMACY)).build(),
                HospitalContext.builder().principalUserId(UUID.randomUUID())
                    .assignedRoles(Set.of("ROLE_PATIENT")).build())) {
            when(callerResolver.liveContext(nurse)).thenReturn(refused);
            Throwable asBroadcast = catchThrowable(() -> withRealService.markAsRead(id, nurse));
            Throwable asForeign = catchThrowable(() -> withRealService.markAsRead(foreign, nurse));
            assertThat(asBroadcast).isInstanceOf(ResourceNotFoundException.class);
            assertThat(asBroadcast.getMessage().replace(id.toString(), "<id>"))
                .isEqualTo(asForeign.getMessage().replace(foreign.toString(), "<id>"));
            assertThat(broadcast.isRead()).isFalse();
        }

        // A doctor whose request went through the filter fallback: nothing in
        // the holder, the live context resolved from the principal.
        when(callerResolver.liveContext(nurse)).thenReturn(HospitalContext.builder()
            .principalUserId(UUID.randomUUID()).assignedRoles(Set.of("ROLE_DOCTOR")).build());
        assertThat(HospitalContextHolder.getContext()).isEmpty();
        assertThat(withRealService.markAsRead(id, nurse).getStatusCode().value()).isEqualTo(200);
        assertThat(broadcast.isRead()).isTrue();
    }

    @Test
    @DisplayName("no principal: 401, nothing marked")
    void noPrincipal() {
        assertThat(controller.markAsRead(UUID.randomUUID(), null).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(service);
    }
}
