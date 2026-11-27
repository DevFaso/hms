package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4106, D4155, D4118 (r81): the wording of the notices of the urgent-alert ladder, for every alert it carries.
 *
 * <ul>
 *   <li>A step says what it did: the first passes the alert to the covering clinician, the second to the
 *       médecin-chef, and all say for how long it has waited. « rappel 1 », « rappel 2 » told nobody that a senior
 *       had been brought in, and the documenter read « rappel 3 » where the clinicians read « non résolue ».</li>
 *   <li>The ladder's notices end with the instruction to acknowledge; an alert sentence they quote never repeats
 *       it (« Ouvrez le compte rendu et accusez réception. Ouvrez le dossier et accusez réception… »).</li>
 *   <li>One spelling of « contresignature » in the ladder's French labels, and no « toujours acquitté par aucun
 *       clinicien ».</li>
 *   <li>A title names its source once (« Alerte NEWS2 (NEWS2, élevé) »).</li>
 * </ul>
 */
class UrgentAlertNoticeWordingGuardTest {

    private static final Path BUNDLES = Path.of("src/main/resources/i18n");
    private static final List<String> LANGUAGES = List.of("", "_en", "_fr", "_es");
    /** An instruction to acknowledge, in any of the bundles' languages. */
    private static final Pattern ACKNOWLEDGE = Pattern.compile(
        "(?i)(accusez réception|acquittez|\\backnowledge\\b|confirme esta|confírmela)");

    private static Properties bundle(String family, String language) throws IOException {
        Properties texts = new Properties();
        try (InputStream in = Files.newInputStream(BUNDLES.resolve(family + language + ".properties"))) {
            texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return texts;
    }

    @Test
    @DisplayName("the first two steps name who the alert was passed to, and every step says how long it waited")
    void eachStepSaysWhatItDid() throws IOException {
        Map<String, String> expected = Map.of(
            "urgent.alert.step.cover", "clinicien de couverture",
            "urgent.alert.step.lead", "médecin-chef");
        for (String language : LANGUAGES) {
            Properties texts = bundle("messages-clinical", language);
            for (String key : List.of("urgent.alert.step.cover", "urgent.alert.step.lead",
                    "urgent.alert.documenter.waiting", "urgent.alert.unresolved")) {
                assertThat(texts.getProperty(key)).as(key + language).isNotBlank()
                    .contains("{0}").contains("{1}").contains("{2}").contains("{3}");
            }
        }
        Properties french = bundle("messages-clinical", "_fr");
        expected.forEach((key, step) -> assertThat(french.getProperty(key)).as(key)
            .contains(step).doesNotContainIgnoringCase("rappel"));
        assertThat(french.getProperty("urgent.alert.documenter.waiting")).doesNotContainIgnoringCase("rappel");
        String worker = Files.readString(Path.of(
            "src/main/java/com/example/hms/service/maternity/ObstetricAlertEscalationWorker.java"));
        int at = worker.indexOf("String noticeKey = ");
        assertThat(at).as("the step's notice is chosen by the step").isPositive();
        assertThat(worker.substring(at, worker.indexOf(';', at))).contains("stepNoticeKey(step)");
        assertThat(worker).doesNotContain("notifyQuietly(DOCUMENTER_REMINDER_KEY");
    }

    @Test
    @DisplayName("an alert sentence the ladder quotes never carries its own instruction to acknowledge")
    void noAlertSentenceRepeatsTheInstruction() throws IOException {
        List<String> repeated = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(BUNDLES, "messages-*.properties")) {
            for (Path file : files) {
                Properties texts = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
                for (String key : texts.stringPropertyNames()) {
                    boolean alertSentence = key.startsWith("urgentAlert.msg.") || key.contains(".alert.msg.");
                    if (alertSentence && ACKNOWLEDGE.matcher(texts.getProperty(key)).find()) {
                        repeated.add(file.getFileName() + ": " + key);
                    }
                }
            }
        }
        assertThat(repeated).as("the step notices already say to acknowledge").isEmpty();
    }

    @Test
    @DisplayName("French: one spelling of « contresignature », no « toujours acquitté par aucun », one source per title")
    void frenchWording() throws IOException {
        Properties clinical = bundle("messages-clinical", "_fr");
        for (String key : clinical.stringPropertyNames()) {
            if (key.startsWith("urgentAlert.") || key.startsWith("urgentIncident.") || key.startsWith("urgent.alert.")) {
                assertThat(clinical.getProperty(key)).as(key).doesNotContain("contre-signature");
            }
        }
        for (String family : List.of("messages-clinical", "messages-lab", "messages-maternity")) {
            Properties texts = bundle(family, "_fr");
            for (String key : texts.stringPropertyNames()) {
                assertThat(texts.getProperty(key)).as(family + ": " + key).doesNotContain("toujours acquitté par aucun");
            }
        }
        for (String language : LANGUAGES) {
            Properties texts = bundle("messages-clinical", language);
            // The type label already names NEWS2: the title does not name it again.
            assertThat(texts.getProperty("news2.alert.notification")).as(language).doesNotContain("NEWS2");
        }
    }
}
