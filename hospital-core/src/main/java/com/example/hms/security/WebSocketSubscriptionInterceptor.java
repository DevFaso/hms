package com.example.hms.security;

import com.example.hms.config.SecurityConstants;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.provider.ProviderCallerResolver;
import com.example.hms.security.provider.ProviderConfinementPolicy;
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
 * Per-destination authorization for STOMP SUBSCRIBE frames, and the provider
 * rule for SEND frames.
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
 *   <li>A provider user may subscribe to nothing else (provider plan §6.4).</li>
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
 * <p>SEND frames (the {@code @MessageMapping} handlers, e.g.
 * {@code /app/chat.sendMessage}) are refused to a provider user, as their HTTP
 * twins are by the confinement filter; for everyone else SEND, CONNECT and the
 * rest pass through.
 *
 * <p><b>Who is a provider user</b> is decided exactly as on HTTP: the caller's
 * live context ({@link ProviderCallerResolver}, the same computation as the
 * context filters) and {@link ProviderConfinementPolicy#providerTypes}, so a
 * verified super-admin is exempt. The account is linked exactly as on HTTP
 * (the ws-ticket's user id, or the Keycloak resolver's appUserId rules;
 * nothing else). It is asked once per SUBSCRIBE (outside {@code /user/**}) and
 * per SEND frame, and the tracker check reuses that resolved id. There is no
 * session cache, so an assignment granted or revoked mid-session counts
 * on the next frame. A principal that names no live local account cannot be
 * told apart from a provider user and is treated as one (fail closed); an
 * identified hospital user keeps everything they had.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSubscriptionInterceptor implements ChannelInterceptor {

    private static final String USER_DESTINATION_PREFIX = "/user/";
    private static final String EMERGENCY_BROADCAST_TOPIC = "/topic/emergency-broadcast";
    private static final String NOTIFICATIONS_BROADCAST_TOPIC = "/topic/notifications";

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final ProviderCallerResolver callerResolver;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.SEND.equals(accessor.getCommand())) {
            // Provider rule for @MessageMapping: closed, as HTTP /chat/send is.
            // A SEND with no principal is refused, as a SUBSCRIBE is.
            Principal sender = accessor.getUser();
            if (sender == null) {
                throw denied(null, accessor.getDestination(), "missing principal");
            }
            if (isProvider(resolveOnce(sender))) {
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
        // Resolved once for this frame; /user/** above costs nothing.
        HospitalContext caller = resolveOnce(user);
        if (isProvider(caller)) {
            throw denied(user, destination, "provider users may subscribe to /user/** only");
        }

        if (EMERGENCY_BROADCAST_TOPIC.equals(destination)
                || NOTIFICATIONS_BROADCAST_TOPIC.equals(destination)) {
            return message;
        }

        if (destination.startsWith(PatientTrackerEventPublisher.TOPIC_PREFIX)) {
            authorizeTrackerSubscription(user, caller, destination);
            return message;
        }

        throw denied(user, destination, "destination not in the subscription whitelist");
    }

    private void authorizeTrackerSubscription(Principal user, HospitalContext caller, String destination) {
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

        UUID userId = ProviderCallerResolver.linkedUserId(caller);
        if (userId == null) {
            throw denied(user, destination, "principal carries no user id");
        }
        if (!assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(userId, hospitalId)) {
            throw denied(user, destination, "no active assignment at hospital");
        }
    }

    /**
     * The caller's live context, linked exactly as on HTTP, computed once for
     * the frame; {@code null} when it cannot be computed.
     */
    private HospitalContext resolveOnce(Principal user) {
        try {
            return callerResolver.liveContext(user);
        } catch (RuntimeException unavailable) {
            log.warn("[STOMP] Live context unavailable ({}); treated as a provider user",
                unavailable.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The HTTP rule: the live context's provider types, empty for a verified
     * super-admin. A caller that links no local account, or whose context
     * cannot be computed, is treated as a provider (fail closed).
     */
    private static boolean isProvider(HospitalContext context) {
        if (context == null || context.getPrincipalUserId() == null) {
            return true;
        }
        return !ProviderConfinementPolicy.providerTypes(context).isEmpty();
    }

    private static boolean hasAuthority(Principal user, String authority) {
        if (!(user instanceof Authentication auth)) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private static AccessDeniedException denied(Principal user, String destination, String reason) {
        log.warn(
                "Rejected STOMP frame by '{}' to '{}': {}",
                user != null ? user.getName() : "<anonymous>",
                destination,
                reason);
        return new AccessDeniedException("Subscription to this destination is not permitted");
    }
}
