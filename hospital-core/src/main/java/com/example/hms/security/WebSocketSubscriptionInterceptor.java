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
 *   <li>A provider user (an active assignment at a pharmacy or laboratory)
 *       may subscribe to nothing else (provider plan §6.4).</li>
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
 * <p>SUBSCRIBE frames are inspected as above. SEND frames (the
 * {@code @MessageMapping} handlers, e.g. {@code /app/chat.sendMessage}) are
 * refused to a provider user, as their HTTP twins are by the confinement
 * filter; for everyone else SEND, CONNECT and the rest pass through.
 *
 * <p>Whether the user is a provider user is read once per STOMP session (one
 * query, cached in the session attributes), for any principal type that
 * identifies a local account: the ws-ticket's {@link HospitalUserDetails}, a
 * Keycloak token's {@code appUserId}, or the principal name. Only a principal
 * that identifies no account is treated as a provider (fail closed).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSubscriptionInterceptor implements ChannelInterceptor {

    private static final String USER_DESTINATION_PREFIX = "/user/";
    private static final String EMERGENCY_BROADCAST_TOPIC = "/topic/emergency-broadcast";
    private static final String NOTIFICATIONS_BROADCAST_TOPIC = "/topic/notifications";

    /** Session attribute caching whether this STOMP session belongs to a provider user. */
    static final String PROVIDER_SESSION_ATTRIBUTE = WebSocketSubscriptionInterceptor.class.getName() + ".providerUser";

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final com.example.hms.repository.UserRepository userRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.SEND.equals(accessor.getCommand())) {
            // Provider rule for @MessageMapping: closed, as HTTP /chat/send is.
            Principal sender = accessor.getUser();
            if (sender != null && isProviderUser(accessor, sender)) {
                throw denied(sender, accessor.getDestination(), "provider users may not send STOMP messages");
            }
            return message;
        }
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        Principal user = accessor.getUser();

        if (user == null || destination == null) {
            throw denied(user, destination, "missing principal or destination");
        }

        if (destination.startsWith(USER_DESTINATION_PREFIX)) {
            return message;
        }

        // Provider rule (provider plan §6.4, T21): a user with a live
        // assignment at a pharmacy or laboratory subscribes to /user/** only.
        // A hospital's emergency alerts, the unaddressed notifications
        // broadcast and every patient tracker are no business of theirs.
        if (isProviderUser(accessor, user)) {
            throw denied(user, destination, "provider users may subscribe to /user/** only");
        }

        if (EMERGENCY_BROADCAST_TOPIC.equals(destination)
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

    /**
     * True when the session's user holds an active assignment at a provider
     * facility. Asked once per STOMP session and cached there. A principal
     * that identifies no local account cannot be told apart from a provider
     * user, so it is treated as one (fail closed); an identified hospital
     * user keeps everything they had.
     */
    private boolean isProviderUser(StompHeaderAccessor accessor, Principal user) {
        java.util.Map<String, Object> session = accessor.getSessionAttributes();
        if (session != null && session.get(PROVIDER_SESSION_ATTRIBUTE) instanceof Boolean cached) {
            return cached;
        }
        UUID userId = resolveUserId(user);
        boolean provider = userId == null || assignmentRepository.existsActiveAtProviderFacility(userId);
        if (session != null) {
            session.put(PROVIDER_SESSION_ATTRIBUTE, provider);
        }
        return provider;
    }

    private static boolean hasAuthority(Principal user, String authority) {
        if (!(user instanceof Authentication auth)) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    /**
     * The local account behind the principal: the ws-ticket's user details, a
     * Keycloak token's {@code appUserId} claim, or else the principal name
     * looked up as a username. {@code null} when none identifies an account.
     */
    private UUID resolveUserId(Principal user) {
        if (user instanceof Authentication auth
                && auth.getPrincipal() instanceof HospitalUserDetails details
                && details.getUserId() != null) {
            return details.getUserId();
        }
        if (user instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt) {
            String appUserId = jwt.getToken().getClaimAsString("appUserId");
            if (appUserId != null) {
                try {
                    return UUID.fromString(appUserId);
                } catch (IllegalArgumentException malformed) {
                    return null;
                }
            }
        }
        String name = user == null ? null : user.getName();
        if (name == null || name.isBlank() || userRepository == null) {
            return null;
        }
        return userRepository.findByUsernameIgnoreCase(name)
                .map(com.example.hms.model.User::getId)
                .orElse(null);
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
