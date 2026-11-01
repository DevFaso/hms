package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-22 merge on #976: four tests asserted {@code hasMessageContaining("a.message.key")} on a
 * {@code BusinessException}. The exception's message is {@code MessageUtil.resolveOrRaw(key)}: the raw
 * key when no message source is set, the translated sentence once a Spring context earlier in the same
 * JVM has set the static source. So the assertion passed locally and failed on CI, depending on test
 * order. Assert {@code .extracting("messageKey").isEqualTo("a.message.key")} instead.
 *
 * <p>Ratchet: the existing sites are counted and may only go down; a new one fails.
 */
class MessageKeyAssertionGuardTest {

    private static final Path TESTS = Path.of("src/test/java");
    private static final Pattern KEY_IN_MESSAGE =
        Pattern.compile("hasMessageContaining\\(\"[a-z][a-zA-Z0-9]*+(?:\\.[a-zA-Z0-9]++)++\"\\)");
    private static final int BASELINE = 61;

    @Test
    void noNewAssertionReadsAMessageKeyOutOfTheResolvedMessage() throws IOException {
        int found = 0;
        try (Stream<Path> walk = Files.walk(TESTS)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = KEY_IN_MESSAGE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    found++;
                }
            }
        }
        assertThat(found)
            .as("hasMessageContaining(\"<message.key>\") depends on test order: assert extracting(\"messageKey\");"
                + " when sites are fixed, lower BASELINE to the count found")
            .isEqualTo(BASELINE);
    }

    @Test
    void theScanSeesTheShape() {
        assertThat(KEY_IN_MESSAGE.matcher(".hasMessageContaining(\"eligibility.provider.none\");").find()).isTrue();
        assertThat(KEY_IN_MESSAGE.matcher(".hasMessageContaining(\"required\");").find()).isFalse();
        assertThat(KEY_IN_MESSAGE.matcher(".hasMessageContaining(\"No eligibility provider.\");").find()).isFalse();
    }
}
