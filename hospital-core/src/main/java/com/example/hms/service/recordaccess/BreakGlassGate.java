package com.example.hms.service.recordaccess;

import com.example.hms.model.BreakGlassSession;
import com.example.hms.repository.BreakGlassSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * E9 #62 — the one question every break-the-glass consumer asks: does this
 * actor hold a live session for this patient at the hospital they are acting
 * in? The policy asks it to stand a Tier B relationship in for a missing
 * carrier, the D3 sites ask it to unlock a foreign sensitive row, and the
 * reach recorder asks it to stamp the session id on the disclosure row.
 *
 * <p>A session declared at another hospital does not count: it was declared
 * for care given there. Request-cached, because a chart read asks several
 * times.
 */
@Component
@RequiredArgsConstructor
public class BreakGlassGate {

    private static final String CACHE_PREFIX = BreakGlassGate.class.getName() + ":";

    private final BreakGlassSessionRepository sessionRepository;
    private final Clock clock;

    /** The cache holder, so an absent session is cached as firmly as a present one. */
    private record Cached(BreakGlassSession session) {
    }

    public Optional<BreakGlassSession> liveSession(UUID actorUserId, UUID patientId, UUID actingHospitalId) {
        if (actorUserId == null || patientId == null || actingHospitalId == null) {
            return Optional.empty();
        }
        String key = CACHE_PREFIX + actorUserId + ":" + patientId + ":" + actingHospitalId;
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getAttribute(key, RequestAttributes.SCOPE_REQUEST) instanceof Cached(BreakGlassSession session)) {
            return Optional.ofNullable(session);
        }
        Optional<BreakGlassSession> live = sessionRepository
            .findLiveForUserAndPatient(actorUserId, patientId, LocalDateTime.now(clock)).stream()
            .filter(s -> s.getHospital() != null && actingHospitalId.equals(s.getHospital().getId()))
            .findFirst();
        if (attrs != null) {
            attrs.setAttribute(key, new Cached(live.orElse(null)), RequestAttributes.SCOPE_REQUEST);
        }
        return live;
    }

    public Optional<UUID> liveSessionId(UUID actorUserId, UUID patientId, UUID actingHospitalId) {
        return liveSession(actorUserId, patientId, actingHospitalId).map(BreakGlassSession::getId);
    }

    /** True when a foreign sensitive row may surface for this actor on this patient here. */
    public boolean isUnlocked(UUID actorUserId, UUID patientId, UUID actingHospitalId) {
        return liveSession(actorUserId, patientId, actingHospitalId).isPresent();
    }
}
