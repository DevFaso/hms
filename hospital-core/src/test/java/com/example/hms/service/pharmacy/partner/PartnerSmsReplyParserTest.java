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

    @Test
    @DisplayName("round 2: 'rupture de stock' is a refusal — 'stock' is not 'ok'")
    void ruptureDeStockIsARefusal() {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed =
                parser.parse("Non 3F2A9B1C, rupture de stock");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().action()).isEqualTo(PartnerSmsReplyParser.Action.REJECT);
        assertThat(parsed.get().refToken()).isEqualTo("3F2A9B1C");
    }

    @Test
    @DisplayName("round 2: keywords are whole words")
    void keywordsAreWholeWords() {
        assertThat(parser.parse("ok 3F2A9B1C").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
        assertThat(parser.parse("oui 3F2A9B1C").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
        assertThat(parser.parse("non 3F2A9B1C").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
        assertThat(parser.parse("refus 3F2A9B1C").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
        assertThat(parser.parse("livr\u00e9 3F2A9B1C").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.CONFIRM_DISPENSE);
        assertThat(parser.parse("stock 3F2A9B1C")).isEmpty();
        assertThat(parser.parse("nonante 3F2A9B1C")).isEmpty();
    }

    @Test
    @DisplayName("round 2: 'livr\u00e9' is the dispense word, not the token")
    void livreIsNotTheToken() {
        assertThat(parser.parse("livr\u00e9 3F2A9B1C").orElseThrow().refToken()).isEqualTo("3F2A9B1C");
    }

    @Test
    @DisplayName("round 3: a refusal decides on its own — a refusal word beats an accept or dispense word")
    void refusalWinsOverOtherFamilies() {
        // The words a pharmacy refuses with name what it cannot do; the round-2
        // ambiguity guard turned these into silence, where the original parser
        // (and the pharmacy) meant REJECT.
        assertThat(parser.parse("Non 3F2A9B1C, on ne peut pas livrer").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
        assertThat(parser.parse("oui non 3F2A9B1C").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
        assertThat(parser.parse("ok 3F2A9B1C mais refus\u00e9").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
    }

    @Test
    @DisplayName("round 3: the reference is still read out of a refusal in free text")
    void refusalKeepsTheReference() {
        assertThat(parser.parse("Non 3F2A9B1C, on ne peut pas livrer").orElseThrow().refToken())
                .isEqualTo("3F2A9B1C");
    }

    @Test
    @DisplayName("round 3: an acceptance next to a dispense claim, with no refusal, stays ambiguous and ignored")
    void acceptPlusDispenseStillAmbiguous() {
        assertThat(parser.parse("oui 3F2A9B1C livr\u00e9")).isEmpty();
        assertThat(parser.parse("ok 3F2A9B1C d\u00e9j\u00e0 dispens\u00e9")).isEmpty();
    }

    /** The real message a pharmacy's handset quotes back, built by the templates. */
    private static String offer() {
        return PartnerSmsTemplates.prescriptionOffer("3F2A9B1C", "Amoxicilline 500mg", "AB");
    }

    @Test
    @DisplayName("round 4: quoting the whole offer and answering 'oui' accepts — the quoted 'refuser' is not the pharmacy's word")
    void quotedOfferThenOuiAccepts() {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed = parser.parse(offer() + " oui");

        assertThat(parsed)
                .as("the offer ends in 'pour refuser', so a quoted copy used to read as a refusal")
                .isPresent();
        assertThat(parsed.get().action()).isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
        assertThat(parsed.get().refToken()).isEqualTo("3F2A9B1C");
    }

    @Test
    @DisplayName("round 4: quoting the whole offer and answering 'non' still refuses")
    void quotedOfferThenNonRefuses() {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed = parser.parse(offer() + " non");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().action()).isEqualTo(PartnerSmsReplyParser.Action.REJECT);
        assertThat(parsed.get().refToken()).isEqualTo("3F2A9B1C");
    }

    @Test
    @DisplayName("round 4: a quoted offer with no answer of its own decides nothing")
    void quotedOfferAloneIsNotAnAnswer() {
        assertThat(parser.parse(offer())).isEmpty();
    }

    @Test
    @DisplayName("round 4: the instructed reply is parsed with its guillemets, exactly as the offer prints it")
    void instructedReplyWithGuillemetsIsAccepted() {
        assertThat(parser.parse("\u00ab 1 3F2A9B1C \u00bb").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
        assertThat(parser.parse("\u00ab 1 3F2A9B1C \u00bb").orElseThrow().refToken())
                .isEqualTo("3F2A9B1C");
        assertThat(parser.parse("\u00ab 2 3F2A9B1C \u00bb").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
        assertThat(parser.parse("\"1 3F2A9B1C\"").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
    }

    @Test
    @DisplayName("round 4: a quoted offer whose leading code is 1 accepts, whatever follows")
    void quotedReplyStartingWithTheCodeWins() {
        assertThat(parser.parse("1 3F2A9B1C " + offer()).orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
    }
}
