package com.example.hms.service.support;

import com.example.hms.model.PatientVitalSign;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1169 (round 21): the pain score was stored as a French sentence
 * ("Douleur : 8/10") and read untranslated in Spanish and English. It is a
 * number now, rendered in the reader's language when read.
 */
class VitalSignNotesTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
        MessageUtil.setMessageSource(null);
    }

    @Test
    void theScoreIsRenderedInTheReadersLanguage() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        PatientVitalSign vital = new PatientVitalSign();
        vital.setPainScore(8);
        vital.setNotes("  agitée ");

        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(VitalSignNotes.display(vital)).isEqualTo("Douleur : 8/10 · agitée");
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
        assertThat(VitalSignNotes.display(vital)).isEqualTo("Dolor: 8/10 · agitée");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(VitalSignNotes.display(vital)).isEqualTo("Pain: 8/10 · agitée");

        assertThat(VitalSignNotes.display(new PatientVitalSign())).isNull();
    }

    /** The guard: the score's sentence is composed only at read time, never stored. */
    @Test
    void noWriterComposesTheScoreIntoStoredText() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith("VitalSignNotes.java")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("\"triage.vitals.painScale\"") || source.contains("\"triage.vitals.fundalHeight\"")) {
                    offenders.add(MAIN.relativize(file).toString());
                }
            }
        }
        assertThat(offenders)
            .as("a vital-sign number composed into text outside VitalSignNotes is stored in one language (QA D1169)")
            .isEmpty();
    }
}
