package com.example.hms.service.impl;

import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.OrganizationLifecycleStatusService;
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
 * Tiny in-memory TTL cache over {@link OrganizationRepository#findIdsByLifecycleStateIn}.
 *
 * <p>The set is small in practice (~tens of orgs total, with a handful in
 * non-active states) so a single-snapshot model is appropriate. Refresh is
 * lazy: callers see a stale snapshot until {@link #cacheTtlMillis} elapses,
 * at which point the next read triggers a DB hit. Lifecycle transitions
 * call {@link #invalidate} to make the next read see the new state immediately.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationLifecycleStatusServiceImpl implements OrganizationLifecycleStatusService {

    private static final Set<OrganizationLifecycleState> BLOCKED_STATES = EnumSet.of(
        OrganizationLifecycleState.SUSPENDED,
        OrganizationLifecycleState.ARCHIVED,
        OrganizationLifecycleState.PENDING_PURGE,
        OrganizationLifecycleState.PURGED
    );

    private final long cacheTtlMillis = 30_000L;

    private final OrganizationRepository organizationRepository;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(null);

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> getBlockedOrganizationIds() {
        Snapshot current = snapshot.get();
        long now = System.currentTimeMillis();
        if (current == null || now - current.loadedAtMillis > cacheTtlMillis) {
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
                organizationRepository.findIdsByLifecycleStateIn(BLOCKED_STATES));
            Snapshot fresh = new Snapshot(Set.copyOf(ids), System.currentTimeMillis());
            snapshot.set(fresh);
            return fresh;
        } catch (RuntimeException ex) {
            // If the DB is unreachable, fall back to a permissive empty set rather
            // than locking every user out. The filter will pass; the spec will
            // pass — we accept the brief gap until the next reload succeeds.
            log.warn("[TENANT-LIFECYCLE-STATUS] Cache reload failed, returning empty set: {}",
                ex.getMessage());
            return new Snapshot(Set.of(), System.currentTimeMillis());
        }
    }

    private record Snapshot(Set<UUID> ids, long loadedAtMillis) { }
}
