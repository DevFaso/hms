package com.example.hms.security;

import com.example.hms.enums.FacilityType;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.provider.ProviderCallerResolver;
import com.example.hms.security.provider.ProviderConfinementPolicy;
import com.example.hms.security.provider.RoleFacilityCompatibility;
import com.example.hms.service.PatientTrackerEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
 *   <li>{@code /topic/patient-tracker/{hospitalId}} — the caller must WORK
 *       there (a live assignment at that hospital in a role other than
 *       PATIENT: a patient registered there sees no other patient's status),
 *       or be a VERIFIED super-admin (a live SUPER_ADMIN assignment, not the
 *       handshake's authority).</li>
 *   <li>Everything else — denied. This includes raw {@code /topic/messages},
 *       which only ever carries frames through the user-destination resolver.</li>
 * </ul>
 *
 * <p>SEND frames go to the application only ({@code /app/**}, the
 * {@code @MessageMapping} handlers, e.g. {@code /app/chat.sendMessage}), for
 * everyone: a client SEND straight to a broker destination ({@code /topic},
 * {@code /queue}, {@code /user}) would forge an emergency broadcast, a tracker
 * event or a message in another user's queue, and is refused. A SEND is also
 * refused without a principal, and to a provider user, as its HTTP twin is by
 * the confinement filter.
 *
 * <p><b>Who the caller is</b> is decided exactly as on HTTP: the live context
 * ({@link ProviderCallerResolver}, linked as the context filters link it) and
 * {@link ProviderConfinementPolicy#providerTypes}, so a verified super-admin is
 * exempt. One resolution serves a frame (none for {@code /user/**}, and none
 * for the broadcasts when the ws-ticket's roles include no role a pharmacy or
 * laboratory accepts: such a caller cannot be a provider user) and is
 * kept in the STOMP session for {@link #RESOLUTION_TTL}, so a grant, a
 * revocation or a demotion counts within that window without an assignment
 * query on every frame.
 *
 * <p><b>Failure.</b> A caller that links no local account is a provider for
 * these rules (fail closed). Only a DATABASE failure (a
 * {@link DataAccessException}, or a transaction that could not start) makes a
 * resolution "unavailable": SEND and the tracker are refused, but the two
 * broadcast topics stay open to a caller known not to be a provider user (the
 * session's last resolution said so, no older than {@link #LAST_KNOWN_MAX_AGE},
 * or the ws-ticket's roles include no role a pharmacy or laboratory accepts),
 * so a database hiccup never silently cuts a clinician off the emergency
 * alerts. That case is logged. Any other failure fails closed everywhere.
 */
@Slf4j
@Component
public class WebSocketSubscriptionInterceptor implements ChannelInterceptor {

    /** How long one resolution of the caller serves a STOMP session. */
    static final Duration RESOLUTION_TTL = Duration.ofSeconds(20);

    /** How old the session's last resolution may be to vouch for a caller while the database is down. */
    static final Duration LAST_KNOWN_MAX_AGE = RESOLUTION_TTL.multipliedBy(5);

    /** Session attribute holding the last successful resolution of the caller. */
    static final String RESOLUTION_ATTRIBUTE = WebSocketSubscriptionInterceptor.class.getName() + ".resolution";

    /** The application destination prefix ({@code WebSocketConfig}): the only place a client may SEND. */
    static final String APPLICATION_DESTINATION_PREFIX = "/app/";

    private static final String USER_DESTINATION_PREFIX = "/user/";
    private static final String EMERGENCY_BROADCAST_TOPIC = "/topic/emergency-broadcast";
    private static final String NOTIFICATIONS_BROADCAST_TOPIC = "/topic/notifications";

    /** A successful resolution of the caller and when it was made. */
    private record Resolution(HospitalContext context, Instant resolvedAt) {
    }

    /**
     * The caller for one frame: the live context, or {@code unavailable} when
     * it could not be computed (with the session's last known one, if any).
     */
    private record Caller(HospitalContext context, boolean unavailable, HospitalContext lastKnown) {
    }

    private final ProviderCallerResolver callerResolver;
    private final Clock clock;

    public WebSocketSubscriptionInterceptor(ProviderCallerResolver callerResolver, Clock clock) {
        this.callerResolver = callerResolver;
        this.clock = clock;
    }

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
            String target = accessor.getDestination();
            if (target == null || !target.startsWith(APPLICATION_DESTINATION_PREFIX)) {
                // A broker destination: a forged broadcast, tracker event or
                // message in someone else's queue. Refused for everyone.
                throw denied(sender, target, "clients send to the application (/app/**) only");
            }
            Caller caller = resolve(accessor, sender);
            if (caller.unavailable() || isProvider(caller.context())) {
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

        boolean broadcast = EMERGENCY_BROADCAST_TOPIC.equals(destination)
            || NOTIFICATIONS_BROADCAST_TOPIC.equals(destination);
        if (broadcast && cannotHoldAProviderRole(user)) {
            // No role a pharmacy or laboratory accepts: cannot be a provider
            // user. No lookup (a reconnect storm costs no database read).
            return message;
        }

        Caller caller = resolve(accessor, user);

        if (broadcast) {
            authorizeBroadcast(user, caller, destination);
            return message;
        }

        // Provider rule (provider plan §6.4, T21): a user with a live
        // assignment at a pharmacy or laboratory subscribes to /user/** only.
        if (caller.unavailable() || isProvider(caller.context())) {
            throw denied(user, destination, "provider users may subscribe to /user/** only");
        }

        if (destination.startsWith(PatientTrackerEventPublisher.TOPIC_PREFIX)) {
            authorizeTrackerSubscription(user, caller.context(), destination);
            return message;
        }

        throw denied(user, destination, "destination not in the subscription whitelist");
    }

    /**
     * The two broadcasts: refused to a provider user. A failed resolution is
     * not an answer: a caller known not to be a provider keeps them.
     */
    private void authorizeBroadcast(Principal user, Caller caller, String destination) {
        if (!caller.unavailable()) {
            if (isProvider(caller.context())) {
                throw denied(user, destination, "provider users may subscribe to /user/** only");
            }
            return;
        }
        boolean knownNonProvider = (caller.lastKnown() != null && !isProvider(caller.lastKnown()))
            || cannotHoldAProviderRole(user);
        if (!knownNonProvider) {
            throw denied(user, destination, "caller could not be resolved");
        }
        log.warn("[STOMP] Live context unavailable; broadcast {} kept for a caller known not to be a provider user",
            destination);
    }

    /** The caller works at the hospital (a non-PATIENT role there), or is a verified super-admin. */
    private static void authorizeTrackerSubscription(Principal user, HospitalContext caller, String destination) {
        if (caller.isSuperAdmin()) {
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

        if (caller.getStaffHospitalIds() == null || !caller.getStaffHospitalIds().contains(hospitalId)) {
            throw denied(user, destination, "no active staff assignment at hospital");
        }
    }

    /**
     * The caller's live context, linked exactly as on HTTP: the session's
     * resolution while it is younger than {@link #RESOLUTION_TTL}, else a new
     * one, kept for the next frames. A failure is reported as such, with the
     * session's last known resolution.
     */
    private Caller resolve(StompHeaderAccessor accessor, Principal user) {
        Map<String, Object> session = accessor.getSessionAttributes();
        Resolution cached = session != null && session.get(RESOLUTION_ATTRIBUTE) instanceof Resolution r ? r : null;
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.resolvedAt().plus(RESOLUTION_TTL))) {
            return new Caller(cached.context(), false, cached.context());
        }
        try {
            HospitalContext context = callerResolver.liveContext(user);
            if (session != null) {
                session.put(RESOLUTION_ATTRIBUTE, new Resolution(context, now));
            }
            return new Caller(context, false, context);
        } catch (DataAccessException | CannotCreateTransactionException unavailable) {
            log.warn("[STOMP] Live context unavailable ({})", unavailable.getClass().getSimpleName());
            boolean recent = cached != null && !now.isAfter(cached.resolvedAt().plus(LAST_KNOWN_MAX_AGE));
            return new Caller(null, true, recent ? cached.context() : null);
        } catch (RuntimeException failure) {
            // Not a database outage: no answer at all, so fail closed (as an
            // unlinked caller), never "unavailable".
            log.warn("[STOMP] Caller resolution failed ({}); refused", failure.getClass().getSimpleName());
            return new Caller(null, false, null);
        }
    }

    /**
     * The HTTP rule: the live context's provider types, empty for a verified
     * super-admin. A caller that links no local account is treated as a
     * provider (fail closed).
     */
    private static boolean isProvider(HospitalContext context) {
        if (context == null || context.getPrincipalUserId() == null) {
            return true;
        }
        return !ProviderConfinementPolicy.providerTypes(context).isEmpty();
    }

    /**
     * Without a database: the ws-ticket's account holds no role a pharmacy or
     * laboratory accepts, so it cannot hold a provider assignment.
     */
    private static boolean cannotHoldAProviderRole(Principal user) {
        if (!(user instanceof Authentication auth) || !(auth.getPrincipal() instanceof HospitalUserDetails)) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String role = RoleFacilityCompatibility.bare(authority.getAuthority());
            if (RoleFacilityCompatibility.providerRoles(FacilityType.PHARMACY).contains(role)
                || RoleFacilityCompatibility.providerRoles(FacilityType.LABORATORY).contains(role)) {
                return false;
            }
        }
        return true;
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
