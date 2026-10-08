package com.example.hms.security;

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

/**
 * The STOMP provider rule (provider plan §6.4, AC-8, T21): a user with a live
 * assignment at a pharmacy or laboratory subscribes to {@code /user/**} only.
 * The emergency broadcast, the unaddressed notifications broadcast and every
 * patient tracker (their own facility's included) are refused.
 */
@ExtendWith(MockitoExtension.class)
class ProviderStompSubscriptionTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;

    @InjectMocks private WebSocketSubscriptionInterceptor interceptor;

    private final MessageChannel channel = mock(MessageChannel.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID providerFacilityId = UUID.randomUUID();

    private Principal user(String role) {
        var details = new CustomUserDetails(userId, "pharm1", "n/a", true,
            List.of(new SimpleGrantedAuthority(role)));
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    private static Message<byte[]> subscribe(String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(user);
        accessor.setSessionId("s1");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("a provider user may subscribe to /user/** and nothing else")
    void providerUserGetsUserDestinationsOnly() {
        when(assignmentRepository.existsActiveAtProviderFacility(userId)).thenReturn(true);
        Principal pharmacist = user("ROLE_PHARMACIST");

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
    @DisplayName("a hospital user keeps the two broadcasts")
    void hospitalUserKeepsBroadcasts() {
        when(assignmentRepository.existsActiveAtProviderFacility(userId)).thenReturn(false);
        Principal nurse = user("ROLE_NURSE");
        for (String destination : List.of("/topic/emergency-broadcast", "/topic/notifications")) {
            Message<byte[]> message = subscribe(destination, nurse);
            assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        }
    }

    @Test
    @DisplayName("a principal without a user id cannot be told apart from a provider: broadcasts refused")
    void unidentifiedPrincipalIsTreatedAsAProvider() {
        Principal anonymousShape = new UsernamePasswordAuthenticationToken("someone", null,
            List.of(new SimpleGrantedAuthority("ROLE_NURSE")));
        Message<byte[]> broadcast = subscribe("/topic/emergency-broadcast", anonymousShape);
        assertThatThrownBy(() -> interceptor.preSend(broadcast, channel)).isInstanceOf(AccessDeniedException.class);

        Message<byte[]> own = subscribe("/user/queue/replies", anonymousShape);
        assertThat(interceptor.preSend(own, channel)).isSameAs(own);
    }
}
