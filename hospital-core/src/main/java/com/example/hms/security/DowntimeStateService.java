package com.example.hms.security;

import com.example.hms.model.platform.PlatformDowntimeState;
import com.example.hms.repository.PlatformDowntimeStateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Cached read of the downtime singleton (P3 #23a), mirroring
 * {@link GlobalSessionRevocationService}: load at startup, refresh every
 * 30 s, update the volatile cache synchronously on toggle. The 30 s
 * ceiling is the documented cross-instance propagation bound; a toggle on
 * the same instance is instantaneous. Fail-open: a missing row or a DB
 * blip never turns read-only mode ON by accident (absence = normal
 * operation; a blip keeps the previous value).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DowntimeStateService {

    /** Immutable snapshot the filter and status endpoint read. */
    public record DowntimeSnapshot(boolean readOnly, String message, Instant activatedAt) {
        public static final DowntimeSnapshot NORMAL = new DowntimeSnapshot(false, null, null);
    }

    private final PlatformDowntimeStateRepository repository;

    /** AtomicReference rather than a volatile field: the snapshot record is
     *  immutable so volatile would suffice, but Sonar (java:S3077) cannot
     *  see record immutability — and the atomic type documents the intent. */
    private final java.util.concurrent.atomic.AtomicReference<DowntimeSnapshot> cached =
        new java.util.concurrent.atomic.AtomicReference<>(DowntimeSnapshot.NORMAL);

    @PostConstruct
    void init() {
        refresh();
    }

    @Scheduled(fixedDelay = 30_000L)
    public void refresh() {
        try {
            cached.set(repository.findById(PlatformDowntimeState.SINGLETON_ID)
                .map(row -> new DowntimeSnapshot(row.isReadOnly(), row.getMessage(), row.getActivatedAt()))
                .orElse(DowntimeSnapshot.NORMAL));
        } catch (RuntimeException ex) {
            // Keep the previous value on a DB blip — during an actual outage
            // this is exactly when the mode must keep holding.
            log.warn("[DOWNTIME] Failed to refresh downtime state, keeping cached value: {}",
                ex.getMessage());
        }
    }

    public DowntimeSnapshot snapshot() {
        return cached.get();
    }

    @Transactional
    public DowntimeSnapshot setReadOnly(boolean readOnly, String message, String actorUsername) {
        Instant now = Instant.now();
        PlatformDowntimeState row = repository.findById(PlatformDowntimeState.SINGLETON_ID)
            .orElseGet(() -> {
                PlatformDowntimeState seeded = new PlatformDowntimeState();
                seeded.setId(PlatformDowntimeState.SINGLETON_ID);
                return seeded;
            });
        row.setReadOnly(readOnly);
        row.setMessage(readOnly ? message : null);
        row.setActivatedAt(readOnly ? now : null);
        row.setActivatedByUsername(readOnly ? actorUsername : null);
        row.setUpdatedAt(now);
        repository.save(row);
        DowntimeSnapshot snapshot =
            new DowntimeSnapshot(row.isReadOnly(), row.getMessage(), row.getActivatedAt());
        cached.set(snapshot);
        return snapshot;
    }
}
