package com.example.hms.security;

import com.example.hms.enums.FacilityType;
import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.security.provider.ProviderCallerResolver;
import com.example.hms.security.tenant.ActingScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The STOMP provider rule (provider plan §6.4, AC-8, T21), decided exactly as
 * on HTTP: the caller's live context and its provider types, so a verified
 * super-admin is exempt. A provider user subscribes to {@code /user/**} only
 * and sends no STOMP message. The account is linked exactly as on HTTP: the
 * ws-ticket's user id, or the Keycloak resolver's appUserId rules (a live
 * account whose name matches the principal), nothing else. Resolved once per
 * frame that needs it, and never for {@code /user/**}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProviderStompSubscriptionTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private TenantRoleAssignmentAccessor assignmentAccessor;
    @Mock private MessageChannel channel;

    private WebSocketSubscriptionInterceptor interceptor;

    private final UUID userId = UUID.randomUUID();
    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        ActingScopeResolver actingScopeResolver = new ActingScopeResolver(assignmentAccessor, assignmentRepository,
            mock(ObjectProvider.class));
        KeycloakHospitalContextResolver keycloak = new KeycloakHospitalContextResolver(userRepository, actingScopeResolver);
        ObjectProvider<KeycloakHospitalContextResolver> keycloakProvider = mock(ObjectProvider.class);
        when(keycloakProvider.getIfAvailable()).thenReturn(keycloak);
        interceptor = new WebSocketSubscriptionInterceptor(assignmentRepository,
            new ProviderCallerResolver(assignmentAccessor, keycloakProvider));
    }

    private void holds(TenantRoleAssignment... assignments) {
        when(assignmentAccessor.findAssignmentsForUser(userId)).thenReturn(List.of(assignments));
    }

    private TenantRoleAssignment pharmacist() {
        return new TenantRoleAssignment(pharmacyId, null, "ROLE_PHARMACIST", "PHARMACIST", true, FacilityType.PHARMACY);
    }

    private TenantRoleAssignment nurse() {
        return new TenantRoleAssignment(hospitalId, null, "ROLE_NURSE", "NURSE", true, FacilityType.HOSPITAL);
    }

    private static TenantRoleAssignment superAdmin() {
        return new TenantRoleAssignment(null, null, "ROLE_SUPER_ADMIN", "SUPER_ADMIN", true, null);
    }

    private Principal ticketUser(String role) {
        var details = new CustomUserDetails(userId, "pharm1", "n/a", true,
            List.of(new SimpleGrantedAuthority(role)));
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    private Principal keycloakUser(String preferredUsername, String role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
            .claim("appUserId", userId.toString()).claim("preferred_username", preferredUsername)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)), preferredUsername);
    }

    private User account(String username) {
        User account = new User();
        account.setId(userId);
        account.setUsername(username);
        account.setEmail(username + "@clinic.test");
        return account;
    }

    private static Message<byte[]> frame(StompCommand command, String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        if (user != null) {
            accessor.setUser(user);
        }
        accessor.setSessionId("s1");
        accessor.setSessionAttributes(new HashMap<>());
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> subscribe(String destination, Principal user) {
        return frame(StompCommand.SUBSCRIBE, destination, user);
    }

    @Test
    @DisplayName("a provider user may subscribe to /user/** and nothing else")
    void providerUserGetsUserDestinationsOnly() {
        holds(pharmacist());
        Principal pharmacist = ticketUser("ROLE_PHARMACIST");

        for (String own : List.of("/user/topic/notifications", "/user/queue/replies")) {
            Message<byte[]> message = subscribe(own, pharmacist);
            assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        }
        for (String refused : List.of("/topic/emergency-broadcast", "/topic/notifications",
                                      "/topic/patient-tracker/" + pharmacyId)) {
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
        holds(pharmacist());
        Message<byte[]> send = frame(StompCommand.SEND, "/app/chat.sendMessage", ticketUser("ROLE_PHARMACIST"));

        assertThatThrownBy(() -> interceptor.preSend(send, channel)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a SEND with no principal is refused, as a SUBSCRIBE is")
    void sendWithoutPrincipalIsRefused() {
        Message<byte[]> send = frame(StompCommand.SEND, "/app/chat.sendMessage", null);

        assertThatThrownBy(() -> interceptor.preSend(send, channel)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a verified super-admin is exempt, as on HTTP, even holding a provider assignment")
    void verifiedSuperAdminIsExempt() {
        holds(superAdmin(), pharmacist());
        Principal admin = ticketUser("ROLE_SUPER_ADMIN");

        Message<byte[]> broadcast = subscribe("/topic/emergency-broadcast", admin);
        assertThat(interceptor.preSend(broadcast, channel)).isSameAs(broadcast);
        Message<byte[]> send = frame(StompCommand.SEND, "/app/chat.sendMessage", admin);
        assertThat(interceptor.preSend(send, channel)).isSameAs(send);
        Message<byte[]> tracker = subscribe("/topic/patient-tracker/" + hospitalId, admin);
        assertThat(interceptor.preSend(tracker, channel)).isSameAs(tracker);
    }

    @Test
    @DisplayName("a mid-session grant counts on the next frame: no session cache")
    void midSessionGrantCountsOnTheNextFrame() {
        when(assignmentAccessor.findAssignmentsForUser(userId))
            .thenReturn(List.of(nurse()))
            .thenReturn(List.of(pharmacist()));
        Principal user = ticketUser("ROLE_NURSE");

        Message<byte[]> before = subscribe("/topic/notifications", user);
        assertThat(interceptor.preSend(before, channel)).isSameAs(before);
        Message<byte[]> after = subscribe("/topic/notifications", user);
        assertThatThrownBy(() -> interceptor.preSend(after, channel)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("one resolution per frame: none for /user/**, one for a tracker frame (its id is reused)")
    void resolvedOncePerFrame() {
        holds(nurse());
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(userId, hospitalId)).thenReturn(true);
        Principal nurse = ticketUser("ROLE_NURSE");

        interceptor.preSend(subscribe("/user/queue/replies", nurse), channel);
        verifyNoInteractions(assignmentAccessor);

        Message<byte[]> tracker = subscribe("/topic/patient-tracker/" + hospitalId, nurse);
        assertThat(interceptor.preSend(tracker, channel)).isSameAs(tracker);
        verify(assignmentAccessor, times(1)).findAssignmentsForUser(userId);
        verify(assignmentRepository).existsByUserIdAndHospitalIdAndActiveTrue(userId, hospitalId);
    }

    @Test
    @DisplayName("a hospital user keeps the two broadcasts and still sends")
    void hospitalUserKeepsBroadcastsAndSend() {
        holds(nurse());
        Principal nurse = ticketUser("ROLE_NURSE");
        for (String destination : List.of("/topic/emergency-broadcast", "/topic/notifications")) {
            Message<byte[]> message = subscribe(destination, nurse);
            assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        }
        Message<byte[]> send = frame(StompCommand.SEND, "/app/chat.sendMessage", nurse);
        assertThat(interceptor.preSend(send, channel)).isSameAs(send);
    }

    @Test
    @DisplayName("a Keycloak token is linked by the HTTP rules: a hospital user keeps the broadcasts, a provider does not")
    void jwtPrincipalIsLinkedAsOnHttp() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(account("kc-nurse")));
        holds(nurse());
        Message<byte[]> nurseBroadcast = subscribe("/topic/emergency-broadcast", keycloakUser("kc-nurse", "ROLE_NURSE"));
        assertThat(interceptor.preSend(nurseBroadcast, channel)).isSameAs(nurseBroadcast);

        holds(pharmacist());
        Message<byte[]> providerBroadcast = subscribe("/topic/notifications", keycloakUser("kc-nurse", "ROLE_PHARMACIST"));
        assertThatThrownBy(() -> interceptor.preSend(providerBroadcast, channel))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a token whose appUserId fails the name match is NOT linked (as on HTTP): refused")
    void jwtFailingNamesMatchIsNotLinked() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(account("someone-else")));
        holds(nurse());
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(userId, hospitalId)).thenReturn(true);

        Message<byte[]> broadcast = subscribe("/topic/emergency-broadcast", keycloakUser("kc-nurse", "ROLE_NURSE"));
        assertThatThrownBy(() -> interceptor.preSend(broadcast, channel)).isInstanceOf(AccessDeniedException.class);
        Message<byte[]> send = frame(StompCommand.SEND, "/app/chat.sendMessage", keycloakUser("kc-nurse", "ROLE_NURSE"));
        assertThatThrownBy(() -> interceptor.preSend(send, channel)).isInstanceOf(AccessDeniedException.class);
        verify(assignmentAccessor, never()).findAssignmentsForUser(any());
    }

    @Test
    @DisplayName("a bare principal whose name matches a local username is NOT linked (as on HTTP): refused")
    void usernamePrincipalIsNotLinked() {
        when(userRepository.findByUsernameIgnoreCase("nurse-by-name")).thenReturn(Optional.of(account("nurse-by-name")));
        holds(nurse());
        Principal named = new UsernamePasswordAuthenticationToken("nurse-by-name", null,
            List.of(new SimpleGrantedAuthority("ROLE_NURSE")));

        Message<byte[]> broadcast = subscribe("/topic/emergency-broadcast", named);
        assertThatThrownBy(() -> interceptor.preSend(broadcast, channel)).isInstanceOf(AccessDeniedException.class);
        Message<byte[]> own = subscribe("/user/queue/replies", named);
        assertThat(interceptor.preSend(own, channel)).isSameAs(own);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("a live context that cannot be computed is a refusal, never a pass")
    void unavailableContextFailsClosed() {
        when(assignmentAccessor.findAssignmentsForUser(userId)).thenThrow(new IllegalStateException("db down"));
        Message<byte[]> broadcast = subscribe("/topic/emergency-broadcast", ticketUser("ROLE_NURSE"));

        assertThatThrownBy(() -> interceptor.preSend(broadcast, channel)).isInstanceOf(AccessDeniedException.class);
    }
}
