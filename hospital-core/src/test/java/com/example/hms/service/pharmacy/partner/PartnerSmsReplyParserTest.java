package com.example.hms.service.pharmacy.partner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser understands the instructed reply and refuses to guess at anything
 * else. Everything it does not understand is reported to staff by
 * {@link PartnerExchangeService}, so "empty" here means "a person reads it",
 * not "silently dropped".
 */
class PartnerSmsReplyParserTest {

    private static final String REF = "3F2A9B1C";

    private final PartnerSmsReplyParser parser = new PartnerSmsReplyParser();

    /** The real offer a handset quotes back, built from the bundle text. */
    private static String offer() {
        return new PartnerSmsTemplates(com.example.hms.i18n.TestMessageSources.bundles())
                .prescriptionOffer(REF, "Amoxicilline 500mg", "AB");
    }

    // ── the instructed reply ────────────────────────────────────────────

    @Test
    @DisplayName("the instructed reply: a code and the reference, as the whole message")
    void instructedReplyAsTheWholeMessage() {
        assertThat(parser.parse("1 " + REF).orElseThrow())
                .isEqualTo(new PartnerSmsReplyParser.ParsedReply(PartnerSmsReplyParser.Action.ACCEPT, REF));
        assertThat(parser.parse("2 " + REF).orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.REJECT);
        assertThat(parser.parse("3 " + REF).orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.CONFIRM_DISPENSE);
    }

    @Test
    @DisplayName("handsets wrap replies: guillemets, quotes, spacing and a trailing stop are tolerated")
    void wrappingIsTolerated() {
        assertThat(parser.parse("« 1 " + REF + " »").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
        assertThat(parser.parse("\"1 " + REF + "\"").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
        assertThat(parser.parse("  1   " + REF + " .").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
        assertThat(parser.parse("1-" + REF).orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
    }

    @Test
    @DisplayName("the reference is returned upper-cased, whatever case it arrives in")
    void referenceIsUpperCased() {
        assertThat(parser.parse("1 abcxyz").orElseThrow().refToken()).isEqualTo("ABCXYZ");
    }

    @Test
    @DisplayName("the instructed form inside a short message of the pharmacy's own")
    void instructedFormInsideAMessage() {
        assertThat(parser.parse("bonjour, « 1 " + REF + " », merci").orElseThrow().action())
                .isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
    }

    // ── everything else goes to a human ─────────────────────────────────

    @Test
    @DisplayName("a code with no reference is not a reply we can route")
    void codeWithoutReferenceIsNotUnderstood() {
        assertThat(parser.parse("1")).isEmpty();
        assertThat(parser.parse("1 a")).isEmpty();
    }

    @Test
    @DisplayName("free text is never interpreted, however clear it looks")
    void freeTextIsNeverInterpreted() {
        // Each of these used to be acted on by the keyword scan.
        assertThat(parser.parse("oui " + REF)).isEmpty();
        assertThat(parser.parse("non " + REF)).isEmpty();
        assertThat(parser.parse("ok " + REF)).isEmpty();
        assertThat(parser.parse("refus " + REF)).isEmpty();
        assertThat(parser.parse("délivré " + REF)).isEmpty();
        assertThat(parser.parse("Non " + REF + ", rupture de stock")).isEmpty();
    }

    @Test
    @DisplayName("the misreadings that drove this rewrite are all simply not understood")
    void theMisreadingsAreGone() {
        // A clinician's note quoted back, whose line begins with a digit: read
        // as the pharmacy's "3", and the patient was told "délivré".
        assertThat(parser.parse(offer() + "\n3 boîtes si possible")).isEmpty();
        // The offer's own clinical text made an acceptance "ambiguous".
        assertThat(parser.parse(offer() + "\nà délivrer en une seule fois. oui")).isEmpty();
        // A note containing "non" turned an acceptance into a refusal.
        assertThat(parser.parse("oui, mais pas avant lundi (non urgent) " + REF)).isEmpty();
        // "il reste 1 boîte" mid-sentence.
        assertThat(parser.parse("refus " + REF + ", il reste 1 boîte")).isEmpty();
    }

    @Test
    @DisplayName("a quoted-back offer decides nothing: it carries both codes, and neither is the pharmacy's")
    void quotedOfferDecidesNothing() {
        assertThat(parser.parse(offer())).isEmpty();
        assertThat(parser.parse(offer() + " oui")).isEmpty();
        // Even with the instructed reply appended: our own « 1 » and « 2 » are
        // in the quote, so nothing here is unambiguously the pharmacy's word.
        assertThat(parser.parse(offer() + "\n« 1 " + REF + " »")).isEmpty();
    }

    @Test
    @DisplayName("blank and null decide nothing")
    void blankDecidesNothing() {
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    // ── candidate references, for naming the prescription to a human ────

    @Test
    @DisplayName("candidate references come from the instructed form, the offer's prefix, and reference-shaped words")
    void candidateReferencesFromStructure() {
        assertThat(parser.candidateReferences(offer() + " oui")).contains(REF);
        assertThat(parser.candidateReferences("« 1 " + REF + " » et merci")).contains(REF);
        assertThat(parser.candidateReferences("oui " + REF)).contains(REF);
    }

    @Test
    @DisplayName("candidate references are bounded, and empty for an empty message")
    void candidateReferencesAreBounded() {
        assertThat(parser.candidateReferences(null)).isEmpty();
        assertThat(parser.candidateReferences("  ")).isEmpty();
        assertThat(parser.candidateReferences("aaa bbb ccc ddd eee fff ggg hhh iii jjj kkk"))
                .hasSizeLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("a reference only in the offer's prefix is still offered as a candidate")
    void candidateFromOfferPrefix() {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed = parser.parse(offer());
        assertThat(parsed).isEmpty();
        assertThat(parser.candidateReferences(offer())).contains(REF);
    }
}
