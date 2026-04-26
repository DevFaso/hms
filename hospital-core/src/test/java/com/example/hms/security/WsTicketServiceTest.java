package com.example.hms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WsTicketServiceTest {

    private WsTicketService wsTicketService;

    @BeforeEach
    void setUp() {
        wsTicketService = new WsTicketService();
    }

    @Test
    void issue_returnsNonBlankTicket() {
        String ticket = wsTicketService.issue("doctor@hms.com");
        assertThat(ticket).isNotBlank();
    }

    @Test
    void redeem_validTicket_returnsUsername() {
        String ticket = wsTicketService.issue("doctor@hms.com");
        String username = wsTicketService.redeem(ticket);
        assertThat(username).isEqualTo("doctor@hms.com");
    }

    @Test
    void redeem_isReusableWithinTtl_returnsSameUsername() {
        // SockJS opens the same handshake URL twice (once for /info transport
        // negotiation, once for the actual transport upgrade). The ticket must
        // remain valid across both calls within its TTL window.
        String ticket = wsTicketService.issue("doctor@hms.com");
        assertThat(wsTicketService.redeem(ticket)).isEqualTo("doctor@hms.com");
        assertThat(wsTicketService.redeem(ticket)).isEqualTo("doctor@hms.com");
        assertThat(wsTicketService.redeem(ticket)).isEqualTo("doctor@hms.com");
    }

    @Test
    void redeem_unknownTicket_returnsNull() {
        assertThat(wsTicketService.redeem("bogus-ticket")).isNull();
    }

    @Test
    void redeem_nullTicket_returnsNull() {
        assertThat(wsTicketService.redeem(null)).isNull();
    }

    @Test
    void redeem_blankTicket_returnsNull() {
        assertThat(wsTicketService.redeem("")).isNull();
    }

    @Test
    void evictExpired_removesOldTickets() {
        // Issue a ticket — it won't be expired yet, so evict shouldn't remove it
        String ticket = wsTicketService.issue("user@hms.com");
        wsTicketService.evictExpired();
        // Ticket should still be redeemable since it hasn't expired
        assertThat(wsTicketService.redeem(ticket)).isEqualTo("user@hms.com");
    }

    @Test
    void issue_multipleTickets_eachIsUnique() {
        String t1 = wsTicketService.issue("user@hms.com");
        String t2 = wsTicketService.issue("user@hms.com");
        assertThat(t1).isNotEqualTo(t2);
        // Both should be independently redeemable
        assertThat(wsTicketService.redeem(t1)).isEqualTo("user@hms.com");
        assertThat(wsTicketService.redeem(t2)).isEqualTo("user@hms.com");
    }
}
