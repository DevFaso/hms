package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA D1286 (round 27; the D665 / D1269 class from the reading side): the
 * NEWS2 escalation is stored with its key and a staff-language (French)
 * copy, and the nurse inbox showed the copy, so ES and EN nurses read French
 * while the doctor's bell (which renders the key) read their own language.
 *
 * <p>Every main method that reads notification rows
 * ({@code notificationRepository.find...} or
 * {@code notificationService.getNotificationsForUser}) and shows their text
 * ({@code getMessage()}, or hands the rows to a response) renders them for
 * the reader first ({@code localized(} / {@code localize(}).
 */
@DisplayName("D1286: every reader of notification rows renders them in the reader's language")
class NotificationReaderLanguageGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern READS = Pattern.compile(
        "notificationRepository\\s*+\\.\\s*+find\\w*+\\(|notificationService\\s*+\\.\\s*+getNotificationsForUser\\(");

    @Test
    void everyNotificationReaderLocalizesBeforeShowingTheText() throws IOException {
        List<String> stored = new ArrayList<>();
        int readers = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith("NotificationServiceImpl.java")) {
                    continue; // the store itself: it returns rows to the readers checked here
                }
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = READS.matcher(source);
                while (m.find()) {
                    String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                    boolean showsText = method.contains(".getMessage()") || method.contains("ResponseEntity");
                    if (!showsText) {
                        continue;
                    }
                    readers++;
                    if (!method.contains("localized(") && !method.contains("localize(")) {
                        stored.add(MAIN.relativize(file) + ":" + SourceScan.lineOf(source, m.start()));
                    }
                }
            }
        }
        assertThat(readers).as("notification readers that show the text").isGreaterThanOrEqualTo(3);
        assertThat(stored)
            .as("a reader shows the staff-language copy stored with the row instead of rendering its key in the "
                + "reader's language (QA D1286)" + SourceScan.MANDATE)
            .isEmpty();
    }
}
