package com.example.hms.service.pharmacy.partner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Additional coverage for fuzzy action-word branches in {@link PartnerSmsReplyParser}
 * that the original test did not exercise (accept/ok/refus/rejet/deliv/délivr/dispen).
 */
class PartnerSmsReplyParserBranchesTest {

    private final PartnerSmsReplyParser parser = new PartnerSmsReplyParser();

    @Test
    @DisplayName("parses 'accept' fuzzy keyword")
    void parsesAccept() {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed = parser.parse("accept ABC12");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().action()).isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
        assertThat(parsed.get().refToken()).isEqualTo("ABC12");
    }

    @Test
    @DisplayName("parses 'ok' fuzzy accept keyword")
    void parsesOk() {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed = parser.parse("ok ABC12");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().action()).isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
    }

    @Test
    @DisplayName("parses 'refus' fuzzy reject keyword")
    void parsesRefus() {
        assertThat(parser.parse("refus ABC99").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
    }

    @Test
    @DisplayName("parses 'rejet' fuzzy reject keyword")
    void parsesRejet() {
        assertThat(parser.parse("rejet ABC99").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
    }

    @Test
    @DisplayName("parses 'deliv' fuzzy dispense keyword")
    void parsesDeliv() {
        assertThat(parser.parse("deliv ABC12").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.CONFIRM_DISPENSE);
    }

    @Test
    @DisplayName("parses 'délivré' fuzzy dispense keyword")
    void parsesDelivre() {
        assertThat(parser.parse("d\u00e9livr\u00e9 ABC12").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.CONFIRM_DISPENSE);
    }

    @Test
    @DisplayName("parses 'dispen' fuzzy dispense keyword")
    void parsesDispen() {
        assertThat(parser.parse("dispen ABC12").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.CONFIRM_DISPENSE);
    }

    @Test
    @DisplayName("returns empty when action word present but no valid token follows")
    void emptyWhenOnlyActionWord() {
        // "oui" alone — no token extracted because action words are skipped
        assertThat(parser.parse("oui")).isEmpty();
    }

    @Test
    @DisplayName("returns empty on completely unknown message")
    void unknownMessage() {
        assertThat(parser.parse("hello world")).isEmpty();
    }
}
