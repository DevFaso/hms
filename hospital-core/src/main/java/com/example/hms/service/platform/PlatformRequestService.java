package com.example.hms.service.platform;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QA D4308: a hospital administrator writes to the platform. The request is
 * filed in every platform administrator's inbox, with the hospital it comes
 * from and who sent it; nothing else stores it and nothing is sent by SMS or
 * e-mail.
 *
 * <p>Each administrator may send {@value #MAX_REQUESTS_PER_HOUR} requests an
 * hour, and the same request sent again within {@link #DUPLICATE_WINDOW}
 * (a second press, a retry) answers the first one's reference and files
 * nothing. Both are kept in memory per instance, like the sign-in buckets.
 */
@Service
@RequiredArgsConstructor
public class PlatformRequestService {

    public static final int MAX_SUBJECT_LENGTH = 120;
    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final int MAX_REQUESTS_PER_HOUR = 10;
    public static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(10);
    private static final Duration RATE_WINDOW = Duration.ofHours(1);

    private final PlatformInbox platformInbox;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;

    /** The requests each administrator filed in the last hour, oldest first. */
    private final Map<UUID, Deque<Sent>> sentByRequester = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    private record Sent(Instant at, UUID hospitalId, String subject, String message, UUID id) { }

    /** For tests: the clock the hourly limit and the duplicate window read. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Files the request and answers its reference. */
    @Transactional(readOnly = true)
    public UUID send(UUID requesterId, String subject, String message) {
        String cleanSubject = subject == null ? "" : subject.trim();
        String cleanMessage = message == null ? "" : message.trim();
        RequiredFields.check()
            .require(!cleanSubject.isEmpty(), "subject", "platformRequest.subject.required")
            .require(!cleanMessage.isEmpty(), "message", "platformRequest.message.required")
            .orThrow();
        if (cleanSubject.length() > MAX_SUBJECT_LENGTH) {
            throw new FieldBusinessException("subject", "platformRequest.subject.tooLong",
                MAX_SUBJECT_LENGTH, cleanSubject.length());
        }
        if (cleanMessage.length() > MAX_MESSAGE_LENGTH) {
            throw new FieldBusinessException("message", "platformRequest.message.tooLong",
                MAX_MESSAGE_LENGTH, cleanMessage.length());
        }
        UUID hospitalId = ActingScopeResolver.pinnedHospitalIdOrNull();
        Hospital hospital = hospitalId == null ? null : hospitalRepository.findClinicalById(hospitalId).orElse(null);
        if (hospital == null) {
            // A request comes from one hospital: the one the administrator is working at.
            throw new BusinessException(RoleValidator.HOSPITAL_CONTEXT_REQUIRED);
        }
        Deque<Sent> sent = sentByRequester.computeIfAbsent(requesterId, id -> new ArrayDeque<>());
        // One request at a time per administrator: a double press waits for the first and is answered by it.
        synchronized (sent) {
            Instant now = clock.instant();
            sent.removeIf(s -> !s.at().isAfter(now.minus(RATE_WINDOW)));
            for (Sent earlier : sent) {
                if (earlier.at().isAfter(now.minus(DUPLICATE_WINDOW))
                        && Objects.equals(earlier.hospitalId(), hospital.getId())
                        && earlier.subject().equals(cleanSubject) && earlier.message().equals(cleanMessage)) {
                    return earlier.id();
                }
            }
            if (sent.size() >= MAX_REQUESTS_PER_HOUR) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "platformRequest.tooMany");
            }
            String requester = userRepository.findById(requesterId).map(PlatformRequestService::nameOf).orElse(null);
            UUID requestId = UUID.randomUUID();
            platformInbox.hospitalRequest(requestId, hospital.getId(), hospital.getName(), requester,
                cleanSubject, cleanMessage);
            sent.addLast(new Sent(now, hospital.getId(), cleanSubject, cleanMessage, requestId));
            return requestId;
        }
    }

    private static String nameOf(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
            + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }
}
