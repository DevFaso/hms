package com.example.hms.exception;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D904: {@link GlobalExceptionHandler#handleConflictException} reads a
 * {@code "field:"} prefix off a 409's message, and it took the text before
 * any first colon as that field. Every assignment-delete refusal ("Impossible
 * de supprimer l'affectation : elle est encore utilisée...", "Cannot delete
 * assignment: ...") reached the toast cut in half, in all three languages.
 *
 * <p>The guard for the whole class: no message in any bundle, in any locale,
 * may look like a field prefix. Any of them can be thrown as a
 * {@link ConflictException} (most 409s resolve a key), so one that did would
 * be split again.
 */
class ConflictMessagePrefixTest {

    private static final Path BUNDLES = Path.of("src", "main", "resources", "i18n");

    @Test
    void noBundleMessageInAnyLocaleReadsAsAFieldPrefix() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(BUNDLES)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".properties")).toList()) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                for (String key : properties.stringPropertyNames()) {
                    checked++;
                    String value = properties.getProperty(key).replace("''", "'");
                    if (GlobalExceptionHandler.CONFLICT_FIELD_PREFIX.matcher(value).lookingAt()) {
                        offenders.add(file.getFileName() + ": " + key + "=" + value);
                    }
                }
            }
        }
        assertThat(checked).as("bundle messages read").isGreaterThan(1000);
        assertThat(offenders).as("messages a 409 would split at their first colon").isEmpty();
    }

    @Test
    void theFieldConventionStillReadsAFieldName() {
        assertThat(GlobalExceptionHandler.CONFLICT_FIELD_PREFIX.matcher("email:Email taken").lookingAt()).isTrue();
        assertThat(GlobalExceptionHandler.CONFLICT_FIELD_PREFIX.matcher("userId:patient.account.staffAccount")
            .lookingAt()).isTrue();
        assertThat(GlobalExceptionHandler.CONFLICT_FIELD_PREFIX.matcher("referenceRanges[0].low:x").lookingAt())
            .isTrue();
    }

    @Test
    void aTranslatedSentenceIsNeverAFieldName() {
        for (String sentence : List.of(
            "Impossible de supprimer l'affectation : elle est encore utilisée par des services.",
            "No se puede eliminar la asignación: todavía la usan servicios.",
            "Cannot delete assignment: it is still used by departments.",
            "Conflict: duplicate",
            "erreur : x",
            "reaction: {0}")) {
            assertThat(GlobalExceptionHandler.CONFLICT_FIELD_PREFIX.matcher(sentence).lookingAt())
                .as(sentence).isFalse();
        }
    }
}
