package com.example.hms.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D976 / D1006: the billing modals ignored Escape, left focus behind them
 * and had no dialog role, and their icon buttons were announced by the icon's
 * ligature ("visibility", "picture_as_pdf", "close").
 *
 * <p>Guard over every template of the staff billing page and the patient
 * portal: each overlay box carries {@code appDialogA11y} (role, aria-modal,
 * focus in and back, Tab trap, Escape), no hand-written {@code role="dialog"}
 * bypasses it, and every button whose only content is an icon has an
 * accessible name.
 */
class PortalBillingDialogA11yGuardTest {

    private static final Path APP = Path.of("..", "hospital-portal", "src", "app");
    private static final List<String> FOLDERS = List.of("billing", "patient-portal");

    /** A backdrop or overlay that holds a dialog box. */
    private static final Pattern OVERLAY = Pattern.compile(
        "class=\"[^\"]*\\b(modal-backdrop|booking-overlay|pre-checkin-overlay|pay-overlay|detail-overlay)\\b");
    private static final Pattern BUTTON = Pattern.compile("<button\\b([^>]*)>(.*?)</button>", Pattern.DOTALL);
    private static final Pattern ICON = Pattern.compile(
        "<span[^>]*material-symbols[^>]*>.*?</span>", Pattern.DOTALL);

    private static List<Path> templates() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String folder : FOLDERS) {
            try (Stream<Path> files = Files.walk(APP.resolve(folder))) {
                files.filter(f -> f.toString().endsWith(".html")).forEach(out::add);
            }
        }
        return out;
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void everyOverlayBoxIsAnAccessibleDialog() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : templates()) {
            String html = Files.readString(file, StandardCharsets.UTF_8);
            int overlays = count(OVERLAY, html);
            int dialogs = count(Pattern.compile("\\bappDialogA11y\\b"), html);
            if (dialogs < overlays) {
                offenders.add(APP.relativize(file) + ": " + overlays + " overlays, " + dialogs + " appDialogA11y");
            }
            if (html.contains("role=\"dialog\"")) {
                offenders.add(APP.relativize(file) + ": hand-written role=\"dialog\" (use appDialogA11y)");
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void everyIconOnlyButtonHasAnAccessibleName() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : templates()) {
            String html = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = BUTTON.matcher(html);
            while (m.find()) {
                String attrs = m.group(1);
                String text = ICON.matcher(m.group(2)).replaceAll("").replaceAll("<[^>]+>", "").trim();
                if (text.isEmpty() && !attrs.contains("aria-label")) {
                    int line = html.substring(0, m.start()).split("\n", -1).length;
                    offenders.add(APP.relativize(file) + ":" + line);
                }
            }
        }
        assertThat(offenders).as("icon-only buttons without aria-label").isEmpty();
    }
}
