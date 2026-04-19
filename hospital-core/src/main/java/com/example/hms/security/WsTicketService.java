package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues single-use, short-lived tickets for WebSocket handshake authentication.
 * <p>
 * Flow:
 * <ol>
 *   <li>Authenticated user calls {@code POST /auth/ws-ticket}</li>
 *   <li>Server generates a random ticket, stores it mapped to the user's username</li>
 *   <li>Frontend connects to {@code /ws-chat?ticket=<ticket>}</li>
 *   <li>Filter calls {@link #redeem(String)} — returns the username once, then deletes the ticket</li>
 * </ol>
 *
 * <p><strong>Limitation:</strong> in-memory; not shared across instances.
 * Replace with Redis-backed store when horizontal scaling is required.
 */
@Slf4j
@Service
public class WsTicketService {

    private static final long TICKET_TTL_MS = 60_000; // 1 minute
    private static final int TICKET_BYTE_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** ticket → TicketEntry(username, expiresAt) */
    private final Map<String, TicketEntry> tickets = new ConcurrentHashMap<>();

    /**
     * Generate a single-use ticket for the given user.
     *
     * @param username the authenticated user's username
     * @return an opaque, URL-safe ticket string
     */
    public String issue(String username) {
        byte[] bytes = new byte[TICKET_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expiresAt = System.currentTimeMillis() + TICKET_TTL_MS;
        tickets.put(ticket, new TicketEntry(username, expiresAt));
        log.debug("[WS-TICKET] Issued ticket for user='{}' expiresAt={}", username, expiresAt);
        return ticket;
    }

    /**
     * Redeem a ticket. Returns the username if the ticket is valid and not expired,
     * then immediately removes it (single-use). Returns {@code null} otherwise.
     */
    public String redeem(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        TicketEntry entry = tickets.remove(ticket);
        if (entry == null) {
            log.debug("[WS-TICKET] Unknown or already-used ticket");
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            log.debug("[WS-TICKET] Expired ticket for user='{}'", entry.username());
            return null;
        }
        log.debug("[WS-TICKET] Redeemed ticket for user='{}'", entry.username());
        return entry.username();
    }

    /** Evict expired tickets every 60 seconds. */
    @Scheduled(fixedRate = 60_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        int before = tickets.size();
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        int evicted = before - tickets.size();
        if (evicted > 0) {
            log.info("[WS-TICKET] Evicted {} expired tickets, {} remaining", evicted, tickets.size());
        }
    }

    private record TicketEntry(String username, long expiresAt) {}
}
