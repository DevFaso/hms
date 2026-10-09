package com.example.hms.security;

import com.example.hms.enums.FacilityType;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.security.provider.ProviderCallerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketSubscriptionInterceptorTest {

    @Mock private TenantRoleAssignmentAccessor assignmentAccessor;

    private WebSocketSubscriptionInterceptor interceptor;

    private final MessageChannel channel = mock(MessageChannel.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    /** The ws-ticket principals of this test link through their own user id; no Keycloak resolver is needed. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<KeycloakHospitalContextResolver> keycloakResolverProvider() {
        return mock(ObjectProvider.class);
    }

    @BeforeEach
    void wire() {
        interceptor = new WebSocketSubscriptionInterceptor(
            new ProviderCallerResolver(assignmentAccessor, keycloakResolverProvider()), Clock.systemUTC());
    }

    private void holds(TenantRoleAssignment... assignments) {
        when(assignmentAccessor.findAssignmentsForUser(userId)).thenReturn(List.of(assignments));
    }

    private static TenantRoleAssignment at(UUID hospital, String role) {
        return new TenantRoleAssignment(hospital, null, role, role, true, FacilityType.HOSPITAL);
    }

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
        holds(at(hospitalId, "ROLE_NURSE"));
        Message<byte[]> message =
                frame(
                        StompCommand.SUBSCRIBE,
                        "/topic/patient-tracker/" + hospitalId,
                        userWithRoles("ROLE_NURSE"));

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void rejectsTrackerSubscriptionForForeignHospital() {
        holds(at(UUID.randomUUID(), "ROLE_NURSE"));
        Message<byte[]> message =
                frame(
                        StompCommand.SUBSCRIBE,
                        "/topic/patient-tracker/" + hospitalId,
                        userWithRoles("ROLE_NURSE"));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifiedSuperAdminBypassesTheAssignmentCheck() {
        holds(at(null, "ROLE_SUPER_ADMIN"));
        Message<byte[]> message =
                frame(
                        StompCommand.SUBSCRIBE,
                        "/topic/patient-tracker/" + hospitalId,
                        userWithRoles("ROLE_SUPER_ADMIN"));

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void rejectsMalformedHospitalId() {
        holds(at(hospitalId, "ROLE_NURSE"));
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
        holds(at(null, "ROLE_PATIENT"));
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
        holds(at(hospitalId, "ROLE_DOCTOR"));
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
        // CONNECT and DISCONNECT pass; a SEND is held to the principal and
        // provider rules (ProviderStompSubscriptionTest).
        for (StompCommand command : List.of(StompCommand.CONNECT, StompCommand.DISCONNECT)) {
            Message<byte[]> message = frame(command, "/topic/patient-tracker/" + hospitalId, null);
            assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        }
    }
}
