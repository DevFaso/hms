package com.example.hms.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA r81 D4118: an alert title names its source once. « Alerte NEWS2 (NEWS2, élevé) pour … » and
 * « Alerte de travail (travail transféré ici) » repeated the source inside the parentheses. For every
 * {@code <source>.alert.notification*} title, the words of the title before its « ({0}) » must not
 * reappear in any {@code <source>.alert.type.*} label filled into it.
 */
class AlertTitleSourceOnceGuardTest {

    private static final Path I18N = Path.of("src/main/resources/i18n");
    private static final String NOTIFICATION = ".alert.notification";
    private static final String TYPE = ".alert.type.";

    @Test
    void anAlertTitleDoesNotRepeatItsSourceInTheTypeLabel() throws IOException {
        List<String> offenders = new ArrayList<>();
        int titles = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(I18N, "messages-*.properties")) {
            for (Path file : files) {
                Properties bundle = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    bundle.load(reader);
                }
                for (String key : bundle.stringPropertyNames()) {
                    int at = key.indexOf(NOTIFICATION);
                    if (at <= 0) {
                        continue;
                    }
                    String title = bundle.getProperty(key);
                    int open = title.indexOf("({0})");
                    if (open <= 0) {
                        continue;
                    }
                    titles++;
                    List<String> titleWords = words(title.substring(0, open));
                    String typePrefix = key.substring(0, at) + TYPE;
                    for (String typeKey : bundle.stringPropertyNames()) {
                        if (!typeKey.startsWith(typePrefix)) {
                            continue;
                        }
                        for (String word : words(bundle.getProperty(typeKey))) {
                            if (titleWords.contains(word)) {
                                offenders.add(file.getFileName() + ": " + key + " + " + typeKey + " repeat « " + word + " »");
                            }
                        }
                    }
                }
            }
        }
        assertThat(titles).as("alert titles found").isGreaterThanOrEqualTo(12);
        assertThat(offenders).as("alert titles that name their source twice (QA r81 D4118)").isEmpty();
    }

    /** The words of four letters or more, lower-cased; short words (de, of, ici) are not a source. */
    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 4 && !w.equals("alert") && !w.equals("alerte") && !w.equals("alerta")) {
                out.add(w);
            }
        }
        return out;
    }
}
