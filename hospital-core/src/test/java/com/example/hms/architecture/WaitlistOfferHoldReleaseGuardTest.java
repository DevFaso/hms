package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1185 (round 22) guard: a waitlist entry that leaves OFFERED without
 * freeing the slot its offer holds. Closing an OFFERED entry answered 204 and
 * the slot stayed HELD, out of every search, for up to 48 hours: decline and
 * the expiry sweep released it, the close did not.
 *
 * <p>Every method of production code that moves a waitlist entry to WAITING
 * or CLOSED must release the offer hold in its own body
 * ({@code releaseOfferedSlot(…)}, or {@code slotInventoryService.release(…)}
 * before booking it). A method that only ever sees entries without an offer is
 * listed here with why. None today.
 */
@DisplayName("A waitlist entry never leaves OFFERED with its slot still held")
class WaitlistOfferHoldReleaseGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:(?:private|public|protected|static|final|synchronized)\\s++)++(?:[\\w<>,.?\\[\\]]++\\s++)++"
            + "(\\w++)\\s*+\\((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+\\)\\s*+(?:throws [\\w., ]++)?+\\{");

    /** A waitlist entry moved out of OFFERED (the entity's status is a string). */
    static final Pattern LEAVES_OFFER = Pattern.compile(
        "\\.setStatus\\(\\s*+(?:WAITLIST_STATUS_(?:CLOSED|WAITING)|\"(?:CLOSED|WAITING)\")\\s*+\\)");

    static final Pattern RELEASES = Pattern.compile("\\breleaseOfferedSlot\\(|\\bslotInventoryService\\.release\\(");

    @Test
    @DisplayName("every move to WAITING or CLOSED releases the offered slot")
    void everyExitFromAnOfferReleasesTheSlot() throws IOException {
        List<String> offenders = new ArrayList<>();
        int writers = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(MAIN.resolve("model"))).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!source.contains("AppointmentWaitlist")) {
                    continue;
                }
                List<String> found = offenders(file.getFileName().toString().replace(".java", ""), source);
                writers += countWriters(source);
                offenders.addAll(found);
            }
        }
        assertThat(writers).as("the scan found the waitlist status writers").isGreaterThanOrEqualTo(4);
        assertThat(offenders)
            .as("a waitlist entry leaves OFFERED and its slot stays HELD (QA D1185)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1185 close and accepts the fix")
    void catchesTheD1185Shape() {
        String before = """
            class S {
                public void closeWaitlistEntry(UUID id, UUID hospitalId, String reason) {
                    AppointmentWaitlist entry = load(id, hospitalId);
                    entry.setCloseReason(reason);
                    entry.setStatus(WAITLIST_STATUS_CLOSED);
                }
            }
            """;
        assertThat(offenders("S", before)).containsExactly("S#closeWaitlistEntry");
        String after = before.replace("entry.setCloseReason(reason);",
            "if (offered) { releaseOfferedSlot(entry); }\n        entry.setCloseReason(reason);");
        assertThat(offenders("S", after)).isEmpty();
    }

    private static int countWriters(String source) {
        int n = 0;
        Matcher m = LEAVES_OFFER.matcher(source);
        while (m.find()) {
            n++;
        }
        return n;
    }

    static List<String> offenders(String file, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (LEAVES_OFFER.matcher(body).find() && !RELEASES.matcher(body).find()) {
                out.add(file + "#" + m.group(1));
            }
        }
        return out;
    }
}
