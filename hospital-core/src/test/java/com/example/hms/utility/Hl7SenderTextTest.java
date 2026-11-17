package com.example.hms.utility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Hl7SenderTextTest {

    private static final String ESC = String.valueOf((char) 0x1B);
    private static final String RLO = String.valueOf((char) 0x202E);
    private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);
    private static final String ZERO_WIDTH_SPACE = String.valueOf((char) 0x200B);
    private static final String BEL = String.valueOf((char) 0x07);

    @Test
    @DisplayName("A value is quoted whole, so it cannot end early and pose as our text")
    void aForgedValueCannotEndItsQuotes() {
        assertThat(Hl7SenderText.quote("x\" not found; cross-tenant rejection (MSH-10 \"y"))
            .isEqualTo("\"x\\\" not found; cross-tenant rejection (MSH-10 \\\"y\"");
    }

    @Test
    @DisplayName("Line breaks, ANSI escapes, bidi overrides and separators are shown as escapes")
    void renderingCharactersAreEscaped() {
        String sent = "a\nb\rc" + ESC + "[31md" + RLO + "e" + LINE_SEPARATOR + "f" + ZERO_WIDTH_SPACE + "g\\h";

        assertThat(Hl7SenderText.quote(sent))
            .isEqualTo("\"a\\u000ab\\u000dc\\u001b[31md\\u202ee\\u2028f\\u200bg\\\\h\"");
    }

    @Test
    @DisplayName("Nothing is trimmed or cut, so values differing only in a trailing control stay distinct")
    void nothingIsTrimmed() {
        String longValue = "V".repeat(300);
        assertThat(Hl7SenderText.quote(longValue)).isEqualTo("\"" + longValue + "\"");
        assertThat(Hl7SenderText.quote(" ABC ")).isEqualTo("\" ABC \"");
        assertThat(Hl7SenderText.quote("ABC" + BEL)).isNotEqualTo(Hl7SenderText.quote("ABC"));
    }

    @Test
    @DisplayName("An absent value is our unquoted marker, which no quoted sender value can equal")
    void anAbsentValueIsTheUnquotedMarker() {
        assertThat(Hl7SenderText.quote(null)).isEqualTo(Hl7SenderText.ABSENT).isEqualTo("(none)");
        assertThat(Hl7SenderText.quote("(none)")).isEqualTo("\"(none)\"");
        assertThat(Hl7SenderText.quote("")).isEqualTo("\"\"");
    }
}
