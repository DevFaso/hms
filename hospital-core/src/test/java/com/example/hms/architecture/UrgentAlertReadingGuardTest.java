package com.example.hms.architecture;

import com.example.hms.service.escalation.UrgentAlertReading;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.maternity.ObstetricAlertEscalationService;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4105 (r81), the class: an urgent alert raised from a reading says what was measured and when. Three
 * postpartum pressures were three identical items giving the rule and not the value.
 *
 * <p>Every source of the ladder that is a reading is either worded through {@link UrgentAlertReading} by the
 * module that raises it, or listed in {@link #VALUE_IN_ITS_OWN_SENTENCE} with the reason. A new source fails here
 * until somebody decides which it is.
 */
class UrgentAlertReadingGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path BUNDLES = Path.of("src/main/resources/i18n");

    /** The sources whose alert is a reading worded with {@link UrgentAlertReading}. */
    private static final Map<String, String> WORDED_WITH_THE_READING = Map.of(
        "SOURCE_POSTPARTUM", ObstetricAlertEscalationService.SOURCE_POSTPARTUM,
        "SOURCE_NEWBORN", ObstetricAlertEscalationService.SOURCE_NEWBORN,
        "SOURCE_NEWBORN_VITAL_SIGN", ObstetricAlertEscalationService.SOURCE_NEWBORN_VITAL_SIGN,
        "SOURCE_URGENT_HYPERTENSION", ObstetricAlertEscalationService.SOURCE_URGENT_HYPERTENSION);

    /** The readings whose own sentence already carries the value. */
    private static final Set<String> VALUE_IN_ITS_OWN_SENTENCE = Set.of(
        // The partograph alert's sentence is written with the measured value (« 164/112 mmHg », the fetal rate).
        ObstetricAlertEscalationService.SOURCE_PARTOGRAPH,
        // The maternal and the adult early warning give the score, which is what the reader acts on.
        ObstetricAlertEscalationService.SOURCE_VITAL_SIGN, ObstetricAlertEscalationService.SOURCE_NEWS2);

    /** The sources that are an event or a record, with nothing measured behind them. */
    private static final Set<String> NOT_A_READING = Set.of(
        ObstetricAlertEscalationService.SOURCE_DELIVERY, ObstetricAlertEscalationService.SOURCE_TRANSFER,
        ObstetricAlertEscalationService.SOURCE_TRANSFUSION_REACTION,
        ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE,
        ObstetricAlertEscalationService.SOURCE_O_POSITIVE_COUNTERSIGN,
        ObstetricAlertEscalationService.SOURCE_INCOMPATIBLE_COUNTERSIGN);

    @Test
    void everySourceOfTheLadderIsClassified() {
        Set<String> classified = new LinkedHashSet<>(WORDED_WITH_THE_READING.values());
        classified.addAll(VALUE_IN_ITS_OWN_SENTENCE);
        classified.addAll(NOT_A_READING);
        assertThat(classified).containsExactlyInAnyOrderElementsOf(ObstetricAlertEscalationService.SOURCE_TYPES);
    }

    @Test
    void everyModuleThatRaisesAReadingAlertWordsItWithTheReading() throws IOException {
        List<String> raisedWithoutTheReading = new ArrayList<>();
        Set<String> raised = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                // The ladder itself reads the sources to pick circles and keys; it raises nothing.
                if (name.startsWith("ObstetricAlert") || name.equals("UrgentAlertOverviewService.java")
                        || name.equals("VitalSetAlertCurrency.java")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String constant : WORDED_WITH_THE_READING.keySet()) {
                    if (source.contains("ObstetricAlertEscalationService." + constant + ",")) {
                        raised.add(constant);
                        if (!source.contains("UrgentAlertReading")) {
                            raisedWithoutTheReading.add(name + " raises " + constant);
                        }
                    }
                }
            }
        }
        assertThat(raisedWithoutTheReading)
            .as("an urgent alert raised from a reading gives the measured value and the time (UrgentAlertReading)")
            .isEmpty();
        assertThat(raised).as("each reading source is raised somewhere this scan can see")
            .containsExactlyInAnyOrderElementsOf(WORDED_WITH_THE_READING.keySet());
    }

    @Test
    void everyKeyOfTheReadingHasItsTextInEveryLanguage() throws IOException {
        for (String suffix : List.of("", "_en", "_fr", "_es")) {
            Properties bundle = new Properties();
            try (Reader reader = Files.newBufferedReader(BUNDLES.resolve("messages-clinical" + suffix + ".properties"),
                    StandardCharsets.UTF_8)) {
                bundle.load(reader);
            }
            for (String key : UrgentAlertReading.KEYS) {
                assertThat(bundle.getProperty(key)).as(key + " in messages-clinical" + suffix).isNotBlank();
            }
        }
    }

    @Test
    void threeReadingsAreThreeDistinguishableAlertsInTheReadersLanguage() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasenames("i18n/messages-clinical", "i18n/messages-maternity");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        NotificationLocalizer localizer = new NotificationLocalizer(messages);
        String rule = "postpartum.alert.msg.postpartum-severe-hypertension";

        Set<String> french = new LinkedHashSet<>();
        int[][] pressures = {{160, 90}, {150, 110}, {162, 98}};
        for (int[] pressure : pressures) {
            UrgentAlertReading.Sentence sentence = UrgentAlertReading
                .postpartum("HYPERTENSION", pressure[0], pressure[1], null, null, null, "05:4" + french.size())
                .wrap(rule, null);
            french.add(localizer.render(sentence.key(), sentence.args(), Locale.FRENCH));
        }
        assertThat(french).hasSize(3);
        assertThat(french).first().asString().contains("160/90 mmHg").contains("05:40").contains("systolique ≥ 160");

        // A newborn's temperature is written the reader's way: « 35,4 » in French, « 35.4 » in English.
        UrgentAlertReading.Sentence cold = UrgentAlertReading
            .newborn("THERMOREGULATION", 35.4, 185, 70, 89, null, null, "05:31")
            .wrap("newborn.alert.msg.newborn-temperature-critical", null);
        assertThat(localizer.render(cold.key(), cold.args(), Locale.FRENCH)).contains("35,4 °C").contains("05:31");
        assertThat(localizer.render(cold.key(), cold.args(), Locale.ENGLISH)).contains("35.4 °C");

        // A finding with no number behind it keeps its sentence and gains the time.
        UrgentAlertReading.Sentence sign = UrgentAlertReading
            .newborn("DANGER_SIGN", null, null, null, null, null, null, "06:02")
            .wrap("newborn.alert.msg.newborn-danger-convulsions", null);
        assertThat(sign.key()).isEqualTo(UrgentAlertReading.WITH_TIME_KEY);
        assertThat(localizer.render(sign.key(), sign.args(), Locale.FRENCH)).contains("06:02");
    }
}
