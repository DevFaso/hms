package com.example.hms.service.impl;

import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.HospitalLifecycleStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mirrors {@link OrganizationLifecycleStatusServiceImpl} for the
 * hospital level (MVP-c batch). Same TTL cache + lazy refresh +
 * fail-permissive posture so a DB blip never locks every hospital out.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HospitalLifecycleStatusServiceImpl implements HospitalLifecycleStatusService {

    private static final Set<HospitalLifecycleState> BLOCKED_STATES = EnumSet.of(
        HospitalLifecycleState.SUSPENDED,
        HospitalLifecycleState.ARCHIVED,
        HospitalLifecycleState.PENDING_PURGE,
        HospitalLifecycleState.PURGED
    );

    private static final long CACHE_TTL_MILLIS = 30_000L;

    private final HospitalRepository hospitalRepository;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(null);

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> getBlockedHospitalIds() {
        Snapshot current = snapshot.get();
        long now = System.currentTimeMillis();
        if (current == null || now - current.loadedAtMillis > CACHE_TTL_MILLIS) {
            current = reload();
        }
        return current.ids;
    }

    @Override
    public void invalidate() {
        snapshot.set(null);
    }

    private Snapshot reload() {
        try {
            Set<UUID> ids = new HashSet<>(
                hospitalRepository.findIdsByLifecycleStateIn(BLOCKED_STATES));
            Snapshot fresh = new Snapshot(Set.copyOf(ids), System.currentTimeMillis());
            snapshot.set(fresh);
            return fresh;
        } catch (RuntimeException ex) {
            log.warn("[HOSPITAL-LIFECYCLE-STATUS] Cache reload failed, returning empty set: {}",
                ex.getMessage());
            return new Snapshot(Set.of(), System.currentTimeMillis());
        }
    }

    private record Snapshot(Set<UUID> ids, long loadedAtMillis) { }
}
