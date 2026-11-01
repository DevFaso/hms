package com.example.hms.service.i18n;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1207 (round 22): "Critical lab result: Kaliémie 2,3 mmol/L" reached an
 * English reader - the value was formatted in the staff locale (French)
 * before the reader was known. A laboratory value in a notification is an
 * argument formatted when the notification is READ, in the reader's locale.
 */
class NotificationLabValueLocaleTest {

    private final MessageSource messages = TestMessageSources.bundles();
    private final NotificationLocalizer localizer = new NotificationLocalizer(messages);

    @Test
    void theValueIsFormattedInEachReadersLocale() {
        Object[] args = {"Kaliémie", NotificationLocalizer.labValue("2.3", "mmol/L"), "Fatimata Zongo"};
        List<Object> stored = NotificationLocalizer.storable(args);

        assertThat(localizer.render("lab.critical.notification", stored, Locale.ENGLISH))
            .contains("Kaliémie 2.3 mmol/L").doesNotContain("2,3");
        assertThat(localizer.render("lab.critical.notification", stored, Locale.FRENCH))
            .contains("Kaliémie 2,3 mmol/L");
        assertThat(localizer.render("lab.critical.notification", stored, Locale.forLanguageTag("es")))
            .contains("Kaliémie 2,3 mmol/L");
    }

    @Test
    void anSmsRendersOnceInTheLocaleItIsRenderedIn() {
        NotificationText text = NotificationText.of("lab.critical.notification", "K",
            NotificationLocalizer.labValue("<0.5", null), "P");
        assertThat(text.render(messages, Locale.ENGLISH)).contains("K <0.5 for");
        assertThat(text.render(messages, Locale.FRENCH)).contains("K <0,5 pour");
    }

    @Test
    void aWordResultIsShownAsEntered() {
        List<Object> stored = NotificationLocalizer.storable(
            new Object[] {"HIV", NotificationLocalizer.labValue("Positif", null), "P"});
        assertThat(localizer.render("lab.critical.notification", stored, Locale.ENGLISH)).contains("HIV Positif for");
    }

    /**
     * The guard: a method that builds a notification (a bell row or its text)
     * never formats a number itself - DisplayNumbers and localizedValue there
     * would fix the locale before the reader is known.
     */
    @Test
    void noNotificationIsBuiltWithANumberAlreadyFormatted() throws IOException {
        Path main = Path.of("src/main/java/com/example/hms");
        Pattern builds = Pattern.compile("createNotification\\(|NotificationText\\.of\\(");
        Pattern formats = Pattern.compile("DisplayNumbers\\.\\w+\\(|localizedValue\\(|ReleaseNotifier\\.valueOf\\(");
        Pattern method = Pattern.compile("(?m)^    [\\w<>\\[\\], .?]+\\s(\\w+)\\([^;{]*\\)\\s*(?:throws [\\w., ]+)?\\{");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = method.matcher(src);
                while (m.find()) {
                    int open = m.end() - 1;
                    String body = src.substring(open, SourceScan.closingBrace(src, open) + 1);
                    if (builds.matcher(body).find() && formats.matcher(body).find()) {
                        offenders.add(main.relativize(file).toString().replace('\\', '/') + "#" + m.group(1));
                    }
                }
            }
        }
        assertThat(offenders)
            .as("a notification built with a number formatted before the reader is known (QA D1207): pass"
                + " NotificationLocalizer.labValue(raw, unit) instead%s", SourceScan.MANDATE)
            .isEmpty();
    }
}
