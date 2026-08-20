package com.example.hms.security;

import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketSubscriptionInterceptorTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;

    @InjectMocks private WebSocketSubscriptionInterceptor interceptor;

    private final MessageChannel channel = mock(MessageChannel.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    private Principal userWithRoles(String... roles) {
        var details =
                new CustomUserDetails(
                        userId,
                        "nurse1",
                        "n/a",
                        true,
                        List.of(roles).stream().map(SimpleGrantedAuthority::new).toList());
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    private Message<byte[]> frame(StompCommand command, String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        accessor.setSessionId("s1");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void allowsTrackerSubscriptionForActiveAssignment() {
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(userId, hospitalId))
                .thenReturn(true);
        Message<byte[]> message =
                frame(
                        StompCommand.SUBSCRIBE,
                        "/topic/patient-tracker/" + hospitalId,
                        userWithRoles("ROLE_NURSE"));

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void rejectsTrackerSubscriptionForForeignHospital() {
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(userId, hospitalId))
                .thenReturn(false);
        Message<byte[]> message =
                frame(
                        StompCommand.SUBSCRIBE,
                        "/topic/patient-tracker/" + hospitalId,
                        userWithRoles("ROLE_NURSE"));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void superAdminBypassesTheAssignmentCheck() {
        Message<byte[]> message =
                frame(
                        StompCommand.SUBSCRIBE,
                        "/topic/patient-tracker/" + hospitalId,
                        userWithRoles("ROLE_SUPER_ADMIN"));

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verify(assignmentRepository, never())
                .existsByUserIdAndHospitalIdAndActiveTrue(any(), any());
    }

    @Test
    void rejectsMalformedHospitalId() {
        Message<byte[]> message =
                frame(
                        StompCommand.SUBSCRIBE,
                        "/topic/patient-tracker/not-a-uuid",
                        userWithRoles("ROLE_NURSE"));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allowsUserScopedAndSystemBroadcastDestinations() {
        Principal user = userWithRoles("ROLE_PATIENT");
        for (String destination :
                List.of(
                        "/user/topic/notifications",
                        "/user/queue/replies",
                        "/topic/emergency-broadcast",
                        "/topic/notifications")) {
            Message<byte[]> message = frame(StompCommand.SUBSCRIBE, destination, user);
            assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        }
    }

    @Test
    void rejectsDestinationsOutsideTheWhitelist() {
        Principal user = userWithRoles("ROLE_DOCTOR");
        for (String destination :
                List.of("/topic/messages", "/queue/anything", "/topic/patient-tracker", "/topic/other")) {
            Message<byte[]> message = frame(StompCommand.SUBSCRIBE, destination, user);
            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .as("destination %s", destination)
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void rejectsSubscribeWithoutPrincipalOrDestination() {
        Message<byte[]> noUser =
                frame(StompCommand.SUBSCRIBE, "/topic/emergency-broadcast", null);
        assertThatThrownBy(() -> interceptor.preSend(noUser, channel))
                .isInstanceOf(AccessDeniedException.class);

        Message<byte[]> noDestination =
                frame(StompCommand.SUBSCRIBE, null, userWithRoles("ROLE_NURSE"));
        assertThatThrownBy(() -> interceptor.preSend(noDestination, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void nonSubscribeFramesPassThroughUntouched() {
        for (StompCommand command :
                List.of(StompCommand.CONNECT, StompCommand.SEND, StompCommand.DISCONNECT)) {
            Message<byte[]> message = frame(command, "/topic/patient-tracker/" + hospitalId, null);
            assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        }
    }
}
