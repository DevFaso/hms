package com.example.hms.cdshooks.bpa;

import com.example.hms.config.MessageBundles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1231 (round 24): the malaria card called the febrile reading the
 * "Dernière température" while a later, lower one stood in the history - the
 * rule picks the newest reading AT OR ABOVE 38.5 °C, not the newest reading.
 * The qSOFA card said "currently met" of criteria gathered across six hours.
 *
 * <p>A BPA card text that claims "latest", "most recent" or "currently" (in
 * any of the three languages) is true only when its rule really reads the
 * newest value. Every such key is listed here with the method that makes it
 * true; a new claim fails until someone checks its rule and adds it. A time
 * window ("in the last 24 h", "6 dernières heures") is not a claim.
 */
@DisplayName("A BPA card claims 'latest' only where its rule reads the newest value (QA D1231)")
class BpaLatestReadingClaimGuardTest {

    private static final Pattern CLAIM = Pattern.compile(
        "(?iuU)\\b(?:most recent|latest|currently|actuellement|actualmente)\\b"
            + "|\\bderni[èe]re?s?\\s(?!\\d|heures)|\\búltim[oa]s?\\s(?!\\d|horas)");

    /** Keys whose claim is true, with the rule method that picks the newest value. */
    private static final Map<String, String> TRUE_CLAIMS = Map.of(
        "cds.bpa.malaria.latestTemperature",
        "MalariaFeverProtocolRule.latestTemperature: the newest temperature by recordedAt, febrile or not",
        "cds.bpa.pph.vitals",
        "ObHemorrhageProtocolRule.detectSignal: the newest reading (vitals arrive newest first) that triggers",
        "cds.bpa.NEWS2_EWS.summary",
        "NewsScoreProtocolRule.latestClinicalVitals: the newest non-home vitals bundle in the window");

    @Test
    @DisplayName("every 'latest' claim on a BPA card is listed with the method that makes it true")
    void everyLatestClaimIsBackedByTheRule() throws IOException {
        TreeSet<String> claiming = new TreeSet<>();
        for (Map.Entry<String, String> entry : bpaValues().entrySet()) {
            if (CLAIM.matcher(entry.getValue()).find()) {
                claiming.add(entry.getKey().substring(entry.getKey().indexOf('|') + 1));
            }
        }
        assertThat(claiming).as("a BPA text claims the latest value; check its rule and list it in TRUE_CLAIMS")
            .isSubsetOf(TRUE_CLAIMS.keySet());
        assertThat(TRUE_CLAIMS.keySet()).as("a listed claim no longer exists: delete its entry").isSubsetOf(claiming);
    }

    @Test
    @DisplayName("the scan sees the D1231 and qSOFA wordings and passes a time window")
    void scanSeesTheOldWordings() {
        assertThat(CLAIM.matcher("Dernière température : {0} °C le {1}.").find()).isTrue();
        assertThat(CLAIM.matcher("Most recent temperature: {0}°C at {1}.").find()).isTrue();
        assertThat(CLAIM.matcher("qSOFA criteria currently met: {0}/3").find()).isTrue();
        assertThat(CLAIM.matcher("Critères qSOFA actuellement remplis : {0}/3").find()).isTrue();
        assertThat(CLAIM.matcher("Última temperatura: {0} °C el {1}.").find()).isTrue();
        assertThat(CLAIM.matcher("Fièvre documentée ≥ 38,5 °C dans les dernières 24 h").find()).isFalse();
        assertThat(CLAIM.matcher("Critères qSOFA remplis au cours des 6 dernières heures : {0}/3").find()).isFalse();
        assertThat(CLAIM.matcher("Criterios qSOFA cumplidos en las últimas 6 horas: {0}/3").find()).isFalse();
    }

    /** Every {@code cds.bpa.*} value of every locale, keyed {@code suffix|key}. */
    private static Map<String, String> bpaValues() throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String area : MessageBundles.AREAS) {
            for (String suffix : MessageBundles.SUFFIXES) {
                Path file = Paths.get("src/main/resources", MessageBundles.resource(area, suffix));
                if (!Files.exists(file)) {
                    continue;
                }
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (line.startsWith("cds.bpa.") && eq > 0) {
                        out.put(suffix + "|" + line.substring(0, eq), line.substring(eq + 1));
                    }
                }
            }
        }
        return out;
    }
}
