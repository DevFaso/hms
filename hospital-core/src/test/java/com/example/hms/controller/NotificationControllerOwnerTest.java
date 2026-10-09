package com.example.hms.controller;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Notification;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.NotificationService;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.service.NotificationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PUT /notifications/{id}/read marks the caller's OWN notification only. It
 * used to mark any notification by id. Someone else's notification answers
 * exactly as an unknown id: the same 404 exception and message.
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
        when(service.markAsRead(id, "nurse1")).thenReturn(true);

        assertThat(controller.markAsRead(id, nurse).getStatusCode().value()).isEqualTo(200);
        verify(service).markAsRead(id, "nurse1");
    }

    @Test
    @DisplayName("someone else's notification answers exactly as an unknown id")
    void foreignNotificationIsAMiss() {
        UUID foreign = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(service.markAsRead(any(), any())).thenReturn(false);

        Throwable asForeign = catchThrowable(() -> controller.markAsRead(foreign, nurse));
        Throwable asUnknown = catchThrowable(() -> controller.markAsRead(unknown, nurse));

        assertThat(asForeign).isInstanceOf(ResourceNotFoundException.class);
        assertThat(asUnknown).isInstanceOf(ResourceNotFoundException.class);
        assertThat(asForeign.getMessage().replace(foreign.toString(), "<id>"))
            .isEqualTo(asUnknown.getMessage().replace(unknown.toString(), "<id>"));
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("a broadcast's one read flag: staff set it; a confined provider user and a patient get the foreign-id 404")
    void broadcastIsMarkedByUnconfinedStaffOnly() {
        UUID id = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        NotificationRepository repository = mock(NotificationRepository.class);
        Notification broadcast = Notification.builder().id(id).message("announcement").read(false).build();
        Notification someoneElses = Notification.builder().id(foreign).message("x").recipientUsername("other")
            .read(false).build();
        when(repository.findById(id)).thenReturn(Optional.of(broadcast));
        when(repository.findById(foreign)).thenReturn(Optional.of(someoneElses));
        NotificationController withRealService = new NotificationController(
            new NotificationServiceImpl(repository, mock(NotificationWebSocketController.class),
                mock(NotificationPreferenceRepository.class), mock(UserRepository.class)),
            mock(ControllerAuthUtils.class));

        for (HospitalContext refused : List.of(
                HospitalContext.builder().assignedRoles(Set.of("ROLE_PHARMACIST"))
                    .providerFacilityTypes(Set.of(FacilityType.PHARMACY)).build(),
                HospitalContext.builder().assignedRoles(Set.of("ROLE_PATIENT")).build())) {
            HospitalContextHolder.setContext(refused);
            Throwable asBroadcast = catchThrowable(() -> withRealService.markAsRead(id, nurse));
            Throwable asForeign = catchThrowable(() -> withRealService.markAsRead(foreign, nurse));
            assertThat(asBroadcast).isInstanceOf(ResourceNotFoundException.class);
            assertThat(asBroadcast.getMessage().replace(id.toString(), "<id>"))
                .isEqualTo(asForeign.getMessage().replace(foreign.toString(), "<id>"));
            assertThat(broadcast.isRead()).isFalse();
        }

        HospitalContextHolder.setContext(HospitalContext.builder().assignedRoles(Set.of("ROLE_NURSE")).build());
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
