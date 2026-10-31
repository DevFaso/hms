package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1096 guard (round 19). Class: "a decision-support card that says a
 * pharmacist or physician must review the dose, built as a plain warning":
 * the under-3-months card announced the review and the order signed and
 * reached the pharmacy as an ordinary order.
 *
 * <p>Every card sentence in {@code cdshooks/} whose English text asks for a
 * review ("must review") must be built by {@code reviewRequired(…)}, the
 * builder that sets {@code CdsCard.reviewRequired}; the signature then holds
 * the order until the review is recorded (ControlledSigningReauthenticationGuardTest
 * pins that ordering).
 */
@DisplayName("A card that asks for a dose review holds the order (QA D1096)")
class CdsReviewCardGuardTest {

    private static final Path CDS = Paths.get("src/main/java/com/example/hms/cdshooks");
    /** {@code builder(text.get("cds.key"} - the method a card sentence is handed to. */
    private static final Pattern CARD_SENTENCE = Pattern.compile("(\\w+)\\(\\s*text\\.get\\(\\s*\"(cds\\.[^\"]+)\"");
    private static final String REVIEW_BUILDER = "reviewRequired";

    @Test
    @DisplayName("every card that asks for a review is built with reviewRequired(...)")
    void reviewCardsHoldTheOrder() throws IOException {
        Properties english = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/i18n/messages-clinical.properties")) {
            assertThat(in).isNotNull();
            english.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Map<String, String> builderByKey = new TreeMap<>();
        try (Stream<Path> files = Files.walk(CDS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = CARD_SENTENCE.matcher(source);
                while (m.find()) {
                    builderByKey.put(m.group(2), m.group(1));
                }
            }
        }
        List<String> reviewKeys = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        builderByKey.forEach((key, builder) -> {
            if (asksForReview(english.getProperty(key))) {
                reviewKeys.add(key);
                if (!REVIEW_BUILDER.equals(builder)) {
                    offenders.add(key + " is built by " + builder + "(...)");
                }
            }
        });
        assertThat(reviewKeys).as("cards that ask for a dose review")
            .contains("cds.pediatric.manualReview.summary", "cds.pediatric.band.none.summary");
        assertThat(offenders)
            .as("a card that asks for a review must hold the order: build it with reviewRequired(...)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan reads the D1096 shape")
    void catchesTheOldShape() {
        Matcher m = CARD_SENTENCE.matcher("cards.add(warning(text.get(\"cds.pediatric.manualReview.summary\", \"…\"");
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isEqualTo("warning");
        assertThat(asksForReview("{0}: child under {1} months - a pharmacist or physician must review the dose")).isTrue();
        assertThat(asksForReview("Limit: {0}.")).isFalse();
    }

    static boolean asksForReview(String english) {
        return english != null && english.toLowerCase(Locale.ROOT).contains("must review");
    }
}
