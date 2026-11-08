package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA D1437: GET /announcements/{unknown id} answered 500: a lookup that finds nothing threw a bare
 * RuntimeException, which the global handler reports as UNEXPECTED_ERROR. An unknown id is a 404 with a
 * localized message: ResourceNotFoundException. Both shapes of the class are scanned, on the code with its
 * comments removed: a bare RuntimeException out of an empty Optional, and an Optional read with get().
 */
@DisplayName("QA D1437: a lookup that finds nothing never answers 500")
class UnknownIdAnswers404GuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern BARE_RUNTIME_ON_EMPTY =
        Pattern.compile("orElseThrow\\(\\s*\\(\\)\\s*->\\s*new\\s+RuntimeException\\(");
    private static final Pattern FIND_THEN_GET =
        Pattern.compile("\\.find[A-Z]\\w*\\([^;{}]*\\)\\s*\\.get\\(\\)");

    /** Not an id from a request. */
    private static final Map<String, String> NOT_A_REQUESTED_ID = Map.of(
        "controller/AuthController.java",
        "the user who has just authenticated, read back during the login itself: nobody asked for an id");

    @Test
    void noLookupOfARequestedIdThrowsABareRuntimeException() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                String code = SourceScan.stripComments(Files.readString(file));
                if (FIND_THEN_GET.matcher(code).find()
                        || (BARE_RUNTIME_ON_EMPTY.matcher(code).find() && !NOT_A_REQUESTED_ID.containsKey(relative))) {
                    found.add(relative);
                }
            }
        }
        assertThat(found).as("an unknown id is ResourceNotFoundException (404, localized)").isEmpty();
    }
}
