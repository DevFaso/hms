package com.example.hms.service.recordaccess;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts the family-access grants refused for each grantor: after
 * {@link #MAX_REFUSALS} in {@link #WINDOW_MS} the grant form answers
 * "too many attempts" until the oldest refusal leaves the window. The form
 * is for naming one or two relatives, not for trying identifiers.
 *
 * <p>Kept in this instance's memory: a restart, or another instance, starts
 * a fresh count.
 */
public final class FamilyGrantThrottle {

    static final int MAX_REFUSALS = 5;
    static final long WINDOW_MS = 60 * 60 * 1000L;
    private static final int MAX_TRACKED = 10_000;

    private final Map<UUID, Deque<Long>> refusals = new ConcurrentHashMap<>();

    /** Whether {@code grantor} has used up the refusals of the current window. */
    public boolean blocked(UUID grantor) {
        return grantor != null && recent(grantor, System.currentTimeMillis()) >= MAX_REFUSALS;
    }

    /** Records one refused grant for {@code grantor}. */
    public void refused(UUID grantor) {
        if (grantor == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (refusals.size() > MAX_TRACKED) {
            refusals.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    return entry.getValue().isEmpty() || now - entry.getValue().peekLast() >= WINDOW_MS;
                }
            });
        }
        Deque<Long> times = refusals.computeIfAbsent(grantor, key -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(now);
        }
    }

    private int recent(UUID grantor, long now) {
        Deque<Long> times = refusals.get(grantor);
        if (times == null) {
            return 0;
        }
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MS) {
                times.removeFirst();
            }
            return times.size();
        }
    }
}
