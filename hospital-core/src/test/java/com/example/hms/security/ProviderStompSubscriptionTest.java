package com.example.hms.security;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The STOMP provider rule (provider plan §6.4, AC-8, T21): a user with a live
 * assignment at a pharmacy or laboratory subscribes to {@code /user/**} only
 * and sends no STOMP message, as the HTTP twins answer 404. The user is
 * identified for every principal type (the ws-ticket's user details, a
 * Keycloak token, a principal name), asked once per session, and an
 * identified hospital user keeps everything they had.
 */
@ExtendWith(MockitoExtension.class)
class ProviderStompSubscriptionTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageChannel channel;

    @InjectMocks private WebSocketSubscriptionInterceptor interceptor;

    private final UUID userId = UUID.randomUUID();
    private final UUID providerFacilityId = UUID.randomUUID();

    private Principal ticketUser(String role) {
        var details = new CustomUserDetails(userId, "pharm1", "n/a", true,
            List.of(new SimpleGrantedAuthority(role)));
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    private Principal keycloakUser(String role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
            .claim("appUserId", userId.toString()).claim("preferred_username", "kc-nurse")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)), "kc-nurse");
    }

    private static Message<byte[]> frame(StompCommand command, String destination, Principal user,
                                         Map<String, Object> session) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setUser(user);
        accessor.setSessionId("s1");
        accessor.setSessionAttributes(session);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> subscribe(String destination, Principal user) {
        return frame(StompCommand.SUBSCRIBE, destination, user, new HashMap<>());
    }

    @Test
    @DisplayName("a provider user may subscribe to /user/** and nothing else")
    void providerUserGetsUserDestinationsOnly() {
        when(assignmentRepository.existsActiveAtProviderFacility(userId)).thenReturn(true);
        Principal pharmacist = ticketUser("ROLE_PHARMACIST");

        for (String own : List.of("/user/topic/notifications", "/user/queue/replies")) {
            Message<byte[]> message = subscribe(own, pharmacist);
            assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        }
        for (String refused : List.of("/topic/emergency-broadcast", "/topic/notifications",
                                      "/topic/patient-tracker/" + providerFacilityId)) {
            Message<byte[]> message = subscribe(refused, pharmacist);
            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .as(refused).isInstanceOf(AccessDeniedException.class);
        }
        // Refused before the tracker's own assignment check could admit their facility.
        verify(assignmentRepository, never()).existsByUserIdAndHospitalIdAndActiveTrue(any(), any());
    }

    @Test
    @DisplayName("a provider user sends no STOMP message (/app/chat.sendMessage), as HTTP /chat/send answers 404")
    void providerUserSendsNothing() {
        when(assignmentRepository.existsActiveAtProviderFacility(userId)).thenReturn(true);
        Message<byte[]> send = frame(StompCommand.SEND, "/app/chat.sendMessage", ticketUser("ROLE_PHARMACIST"),
            new HashMap<>());

        assertThatThrownBy(() -> interceptor.preSend(send, channel)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a hospital user keeps the two broadcasts and still sends")
    void hospitalUserKeepsBroadcastsAndSend() {
        when(assignmentRepository.existsActiveAtProviderFacility(userId)).thenReturn(false);
        Principal nurse = ticketUser("ROLE_NURSE");
        for (String destination : List.of("/topic/emergency-broadcast", "/topic/notifications")) {
            Message<byte[]> message = subscribe(destination, nurse);
            assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        }
        Message<byte[]> send = frame(StompCommand.SEND, "/app/chat.sendMessage", nurse, new HashMap<>());
        assertThat(interceptor.preSend(send, channel)).isSameAs(send);
    }

    @Test
    @DisplayName("a Keycloak (JWT) principal is identified by appUserId: a hospital user keeps the broadcasts")
    void jwtPrincipalIsIdentified() {
        when(assignmentRepository.existsActiveAtProviderFacility(userId)).thenReturn(false);
        Message<byte[]> broadcast = subscribe("/topic/emergency-broadcast", keycloakUser("ROLE_NURSE"));

        assertThat(interceptor.preSend(broadcast, channel)).isSameAs(broadcast);
    }

    @Test
    @DisplayName("a Keycloak (JWT) provider user is refused the broadcasts")
    void jwtProviderIsConfined() {
        when(assignmentRepository.existsActiveAtProviderFacility(userId)).thenReturn(true);
        Message<byte[]> broadcast = subscribe("/topic/notifications", keycloakUser("ROLE_LAB_SCIENTIST"));

        assertThatThrownBy(() -> interceptor.preSend(broadcast, channel)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a bare principal name is identified as a username; only an unknown one fails closed")
    void principalNameIsLookedUp() {
        User account = new User();
        account.setId(userId);
        when(userRepository.findByUsernameIgnoreCase("nurse-by-name")).thenReturn(Optional.of(account));
        when(userRepository.findByUsernameIgnoreCase("nobody")).thenReturn(Optional.empty());
        when(assignmentRepository.existsActiveAtProviderFacility(userId)).thenReturn(false);

        Principal named = new UsernamePasswordAuthenticationToken("nurse-by-name", null,
            List.of(new SimpleGrantedAuthority("ROLE_NURSE")));
        Message<byte[]> allowed = subscribe("/topic/emergency-broadcast", named);
        assertThat(interceptor.preSend(allowed, channel)).isSameAs(allowed);

        Principal unknown = new UsernamePasswordAuthenticationToken("nobody", null,
            List.of(new SimpleGrantedAuthority("ROLE_NURSE")));
        Message<byte[]> refused = subscribe("/topic/emergency-broadcast", unknown);
        assertThatThrownBy(() -> interceptor.preSend(refused, channel)).isInstanceOf(AccessDeniedException.class);
        Message<byte[]> own = subscribe("/user/queue/replies", unknown);
        assertThat(interceptor.preSend(own, channel)).isSameAs(own);
    }

    @Test
    @DisplayName("the provider question is asked once per STOMP session")
    void askedOncePerSession() {
        when(assignmentRepository.existsActiveAtProviderFacility(userId)).thenReturn(false);
        Map<String, Object> session = new HashMap<>();
        Principal nurse = ticketUser("ROLE_NURSE");

        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/emergency-broadcast", nurse, session), channel);
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/notifications", nurse, session), channel);
        interceptor.preSend(frame(StompCommand.SEND, "/app/chat.sendMessage", nurse, session), channel);

        verify(assignmentRepository, times(1)).existsActiveAtProviderFacility(userId);
    }
}
