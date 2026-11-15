package com.example.hms.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1761: the born-before-arrival refusal told the midwife to tick
 * « Né avant l'arrivée », a box that does not exist: the form reads
 * « Naissance avant l'arrivée » (ES and EN drifted the same way). A refusal
 * that names a form control must quote that control's label exactly, in each
 * language. Each pair below is a backend message that tells the user to tick
 * a box, and the portal key of that box; the message must contain the label.
 */
class RefusalQuotesFormLabelGuardTest {

    private static final Path BUNDLES = Path.of("src", "main", "resources", "i18n");
    private static final Path PORTAL_I18N = Path.of("..", "hospital-portal", "src", "assets", "i18n");
    private static final String[] LOCALES = {"fr", "es", "en"};

    /** bundle, message key, portal label key. */
    private static final String[][] PAIRS = {
        {"messages-maternity", "labor.delivery.beforeTransfer", "LABOR.BORN_BEFORE_ARRIVAL"},
        {"messages-maternity", "labor.delivery.bornBeforeArrivalAfterObservation", "LABOR.BORN_BEFORE_ARRIVAL"},
        {"messages-clinical", "prescription.cds.criticalOverrideRequired", "CDS.FORCE_OVERRIDE"},
        {"messages-pharmacy", "prescription.asNeeded.confirm", "ORDER_SETS.FREQUENCY.PRN"},
    };

    @Test
    void everyRefusalQuotesItsCheckboxLabelExactly() throws IOException {
        List<String> drift = new ArrayList<>();
        for (String locale : LOCALES) {
            JsonNode portal = new ObjectMapper().readTree(PORTAL_I18N.resolve(locale + ".json").toFile());
            for (String[] pair : PAIRS) {
                String message = message(pair[0], locale, pair[1]);
                String label = label(portal, pair[2]);
                assertThat(message).as(pair[0] + "_" + locale + " " + pair[1]).isNotNull();
                assertThat(label).as(locale + ".json " + pair[2]).isNotNull();
                if (!normalize(message).contains(normalize(label))) {
                    drift.add(locale + ": " + pair[1] + " does not quote \"" + label + "\"");
                }
            }
        }
        assertThat(drift).isEmpty();
    }

    private static String message(String bundle, String locale, String key) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(BUNDLES.resolve(bundle + "_" + locale + ".properties"),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties.getProperty(key);
    }

    private static String label(JsonNode root, String dottedKey) {
        JsonNode node = root;
        for (String part : dottedKey.split("\\.")) {
            node = node == null ? null : node.get(part);
        }
        return node == null || !node.isTextual() ? null : node.asText();
    }

    /** MessageFormat doubles apostrophes; typographic and straight apostrophes read the same. */
    private static String normalize(String text) {
        return text.replace("''", "'").replace('’', '\'');
    }
}
