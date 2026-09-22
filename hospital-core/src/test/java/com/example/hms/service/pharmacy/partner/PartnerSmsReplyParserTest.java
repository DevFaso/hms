package com.example.hms.service.pharmacy.partner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerSmsReplyParserTest {

    private final PartnerSmsReplyParser parser = new PartnerSmsReplyParser();

    @Test
    @DisplayName("parses numeric accept code with reference token")
    void parsesNumericAccept() {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed = parser.parse("1 ABC12");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().action()).isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
        assertThat(parsed.get().refToken()).isEqualTo("ABC12");
    }

    @Test
    @DisplayName("parses numeric reject code")
    void parsesNumericReject() {
        assertThat(parser.parse("2 xyz99").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
    }

    @Test
    @DisplayName("parses numeric dispense confirmation")
    void parsesConfirmDispense() {
        assertThat(parser.parse("3 abc123").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.CONFIRM_DISPENSE);
    }

    @Test
    @DisplayName("parses French fuzzy accept")
    void parsesFrenchOui() {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed = parser.parse("OUI abc12");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().action()).isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
        assertThat(parsed.get().refToken()).isEqualTo("ABC12");
    }

    @Test
    @DisplayName("parses French fuzzy reject")
    void parsesFrenchNon() {
        assertThat(parser.parse("non ABC99").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
    }

    @Test
    @DisplayName("returns empty on blank message")
    void emptyOnBlank() {
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("returns empty when no token can be extracted")
    void emptyWhenNoToken() {
        assertThat(parser.parse("1")).isEmpty();
    }

    @Test
    @DisplayName("uppercases token and trims whitespace")
    void uppercasesToken() {
        assertThat(parser.parse("  1   abcXYZ ").orElseThrow().refToken())
                .isEqualTo("ABCXYZ");
    }

    @Test
    @DisplayName("G17: a digit inside the text is not an action code — the keyword decides")
    void digitInsideTextDoesNotOverrideKeyword() {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed =
                parser.parse("refus ABC12, il reste 1 bo\u00eete");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().action()).isEqualTo(PartnerSmsReplyParser.Action.REJECT);
        assertThat(parsed.get().refToken()).isEqualTo("ABC12");
    }

    @Test
    @DisplayName("G17: a trailing digit is not an action code either")
    void trailingDigitIsNotAnActionCode() {
        assertThat(parser.parse("non ABC12 2").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
        assertThat(parser.parse("ABC12 1")).isEmpty();
    }

    @Test
    @DisplayName("G17: the leading code still wins over a keyword later in the body")
    void leadingCodeWinsOverLaterKeyword() {
        assertThat(parser.parse("2 ABC12 ok").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
    }
}
