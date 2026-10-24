package com.example.hms.i18n;

import com.example.hms.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D665: a notification the system composes is written with a message key,
 * so each reader gets it in their own language. This fails when the old
 * {@code createNotification(String message, ...)} comes back, when a composed
 * notification is written without a key, or when free text is written from a
 * class that is not on the short list of places where a person typed it.
 */
@DisplayName("Notifications are written with a message key (QA D665)")
class NotificationMessageKeyGuardTest {

    private static final Path MAIN = Paths.get("src/main/java");

    /** The only writers of text a person typed (an administrator's message, a custom reminder). */
    private static final Set<String> LITERAL_WRITERS = Set.of(
        "NotificationService.java",
        "NotificationServiceImpl.java",
        "NotificationController.java",
        "PrenatalSchedulingServiceImpl.java");

    private static final Pattern CREATE = Pattern.compile("\\.createNotification\\(\\s*([^,]+),");

    @Test
    @DisplayName("NotificationService has no createNotification(String message, String recipient[, String type])")
    void theKeylessOverloadsAreGone() {
        List<String> keyless = Arrays.stream(NotificationService.class.getMethods())
            .filter(method -> method.getName().equals("createNotification"))
            .filter(method -> Arrays.stream(method.getParameterTypes()).allMatch(String.class::equals))
            .map(Method::toString)
            .toList();
        assertThat(keyless).as("keyless createNotification overloads").isEmpty();
    }

    @Test
    @DisplayName("every createNotification call passes a key, never composed text")
    void everyCallPassesAKey() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher call = CREATE.matcher(source);
            while (call.find()) {
                String first = call.group(1).trim();
                if (looksLikeComposedText(first)) {
                    offenders.add(MAIN.relativize(file) + ": " + first);
                }
            }
        }
        assertThat(offenders).as("createNotification called with composed text instead of a key").isEmpty();
    }

    @Test
    @DisplayName("free text is written only where a person typed it")
    void literalTextOnlyFromTheShortList() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (Files.readString(file, StandardCharsets.UTF_8).contains("createLiteralNotification(")
                    && !LITERAL_WRITERS.contains(file.getFileName().toString())) {
                offenders.add(MAIN.relativize(file).toString());
            }
        }
        assertThat(offenders).as("createLiteralNotification outside %s", LITERAL_WRITERS).isEmpty();
    }

    /**
     * A key is a string literal of the dotted form, a constant, a variable
     * named for a key, or {@code text.key()}. A rendered message
     * ({@code messageSource.getMessage(...)}, {@code message}, {@code body})
     * is composed text.
     */
    static boolean looksLikeComposedText(String firstArgument) {
        if (firstArgument.startsWith("\"")) {
            return !firstArgument.matches("\"[a-zA-Z0-9]+(\\.[a-zA-Z0-9_]+)+\"");
        }
        return firstArgument.contains("getMessage(")
            || !(firstArgument.matches("[A-Z][A-Z0-9_]*")
                || firstArgument.matches("[a-zA-Z]*[kK]ey")
                || firstArgument.endsWith(".key()"));
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
