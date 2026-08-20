package com.example.hms.security;

import com.example.hms.config.SecurityConstants;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.PatientTrackerEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;

/**
 * Per-destination authorization for STOMP SUBSCRIBE frames.
 *
 * <p>The handshake authenticates the user (ws-ticket flow in
 * {@link JwtAuthenticationFilter}), but the simple broker itself performs no
 * destination checks — without this interceptor any authenticated user could
 * subscribe to {@code /topic/patient-tracker/{anyHospitalId}} and watch
 * another tenant's patient movements. Policy is default-deny:
 *
 * <ul>
 *   <li>{@code /user/**} — allowed; Spring's user-destination resolver scopes
 *       these to the subscribing principal's own session.</li>
 *   <li>{@code /topic/emergency-broadcast} — allowed; system-wide by design.</li>
 *   <li>{@code /topic/notifications} — allowed; broadcast fallback used only
 *       when a notification has no recipient username.</li>
 *   <li>{@code /topic/patient-tracker/{hospitalId}} — requires an active
 *       assignment at that hospital, or {@code ROLE_SUPER_ADMIN} (super-admin
 *       assignments are global, so they have no per-hospital rows).</li>
 *   <li>Everything else — denied. This includes raw {@code /topic/messages},
 *       which only ever carries frames through the user-destination resolver.</li>
 * </ul>
 *
 * <p>Only SUBSCRIBE frames are inspected; CONNECT/SEND/etc. pass through so
 * {@code @MessageMapping} handlers keep their existing behaviour.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSubscriptionInterceptor implements ChannelInterceptor {

    private static final String USER_DESTINATION_PREFIX = "/user/";
    private static final String EMERGENCY_BROADCAST_TOPIC = "/topic/emergency-broadcast";
    private static final String NOTIFICATIONS_BROADCAST_TOPIC = "/topic/notifications";

    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        Principal user = accessor.getUser();

        if (user == null || destination == null) {
            throw denied(user, destination, "missing principal or destination");
        }

        if (destination.startsWith(USER_DESTINATION_PREFIX)
                || EMERGENCY_BROADCAST_TOPIC.equals(destination)
                || NOTIFICATIONS_BROADCAST_TOPIC.equals(destination)) {
            return message;
        }

        if (destination.startsWith(PatientTrackerEventPublisher.TOPIC_PREFIX)) {
            authorizeTrackerSubscription(user, destination);
            return message;
        }

        throw denied(user, destination, "destination not in the subscription whitelist");
    }

    private void authorizeTrackerSubscription(Principal user, String destination) {
        if (hasAuthority(user, SecurityConstants.ROLE_SUPER_ADMIN)) {
            return;
        }

        UUID hospitalId;
        try {
            hospitalId =
                    UUID.fromString(
                            destination.substring(PatientTrackerEventPublisher.TOPIC_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw denied(user, destination, "malformed hospital id");
        }

        UUID userId = resolveUserId(user);
        if (userId == null) {
            throw denied(user, destination, "principal carries no user id");
        }
        if (!assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(userId, hospitalId)) {
            throw denied(user, destination, "no active assignment at hospital");
        }
    }

    private static boolean hasAuthority(Principal user, String authority) {
        if (!(user instanceof Authentication auth)) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private static UUID resolveUserId(Principal user) {
        if (user instanceof Authentication auth
                && auth.getPrincipal() instanceof HospitalUserDetails details) {
            return details.getUserId();
        }
        return null;
    }

    private static AccessDeniedException denied(Principal user, String destination, String reason) {
        log.warn(
                "Rejected STOMP SUBSCRIBE by '{}' to '{}': {}",
                user != null ? user.getName() : "<anonymous>",
                destination,
                reason);
        return new AccessDeniedException("Subscription to this destination is not permitted");
    }
}
