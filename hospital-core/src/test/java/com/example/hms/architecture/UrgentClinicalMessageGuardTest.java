package com.example.hms.architecture;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.service.escalation.UrgentAlerts;
import com.example.hms.service.escalation.UrgentEscalationSupport;
import com.example.hms.service.maternity.ObstetricAlertEscalationService;
import com.example.hms.service.maternity.ObstetricAlertEscalationWorker;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scorecard K4, the class this guard keeps closed: an urgent clinical message is acknowledged, and for as long
 * as it is not, it keeps being sent, to a widening circle of clinicians.
 *
 * <p>Every source of an urgent clinical message is on ONE escalation ladder ({@link UrgentEscalationSupport},
 * the critical-result ladder): acknowledged or escalated, with no cap, to clinicians only. This guard scans the
 * services for the two ways an urgent message leaves (a notification whose type reads as urgent, a CRITICAL
 * in-basket item) and requires each file that does so to be listed here, and to be wired to the ladder unless it
 * is listed with the reason its notice is not urgent on its own. A new source fails here until it is wired.
 * The behaviour is described in {@code docs/security/urgent-message-escalation.md}.
 */
@DisplayName("Every urgent clinical message is acknowledged or escalated on the one ladder (K4)")
class UrgentClinicalMessageGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path DOC = Path.of("../docs/security/urgent-message-escalation.md");

    /** A notification type that reads as urgent: a compound name holding one of these words. */
    private static final Pattern URGENT_TYPE = Pattern.compile(
        "\"(?:[A-Z0-9]+_)+(?:CRITICAL|URGENT|ALERT|ESCALATION|EMERGENCY|CULTURE|HYPERTENSION|REACTION|WARNING|OVERDUE)"
            + "(?:_[A-Z0-9]+)*\""
            + "|\"(?:CRITICAL|URGENT|ALERT|ESCALATION|EMERGENCY|NEWS2|WARNING|OVERDUE)(?:_[A-Z0-9]+)+\"");
    private static final String NOTIFIES = "createNotification(";
    private static final String CRITICAL_ITEM = "InBasketPriority.CRITICAL";

    /** How a file reaches the ladder: it raises an alert on the urgent-alert path, or runs a ladder itself. */
    private static final Pattern LADDER_ENTRY = Pattern.compile(
        "raiseAfterCommit\\(|raiseAlreadyNotified\\(|notifyIfCritical\\(|UrgentEscalationSupport|CriticalResultEscalationLadder");

    private static final String ON_THE_LADDER = null;

    /**
     * Every service file an urgent message leaves from. The value is {@link #ON_THE_LADDER}, or the reason the
     * file's notices need no acknowledgement of their own.
     */
    private static final Map<String, String> SOURCES = sources();

    private static Map<String, String> sources() {
        Map<String, String> sources = new TreeMap<>();
        // The critical laboratory value: the ladder the others are modelled on, and the item its result files.
        sources.put("service/CriticalValueNotificationService.java", ON_THE_LADDER);
        sources.put("service/LabResultServiceImpl.java", ON_THE_LADDER);
        // A critical imaging finding and a critical questionnaire answer: their own acknowledgement, the one ladder.
        sources.put("service/ImagingCriticalNotificationService.java", ON_THE_LADDER);
        sources.put("service/pro/ProScreeningEscalationService.java", ON_THE_LADDER);
        // The urgent-alert path and what the ladder shares (the administrators' flag is raised there).
        sources.put("service/maternity/ObstetricAlertEscalationWorker.java", ON_THE_LADDER);
        sources.put("service/escalation/UrgentEscalationSupport.java", ON_THE_LADDER);
        // Obstetric, newborn and early-warning alerts: the documenter's bell, and the alert raised on the path.
        sources.put("service/impl/LaborServiceImpl.java", ON_THE_LADDER);
        sources.put("service/impl/NewbornAssessmentServiceImpl.java", ON_THE_LADDER);
        sources.put("service/impl/PostpartumCareServiceImpl.java", ON_THE_LADDER);
        sources.put("service/vitals/MaternalEarlyWarningService.java", ON_THE_LADDER);
        sources.put("service/vitals/News2EscalationService.java", ON_THE_LADDER);
        sources.put("service/vitals/VitalSignJudgement.java", ON_THE_LADDER);
        // A positive culture; an emergency release's countersignature past its deadline.
        sources.put("service/impl/MicroCultureServiceImpl.java", ON_THE_LADDER);
        sources.put("service/transfusion/IncompatibleReleaseNotices.java", ON_THE_LADDER);
        sources.put("service/transfusion/OPositiveReleaseNotices.java", ON_THE_LADDER);
        sources.put("service/pharmacy/LowStockAlertDelivery.java",
            "REORDER_ALERT: a stock level for the pharmacy's own staff, not a message about a patient");
        sources.put("service/impl/InBasketServiceImpl.java",
            "the in-basket itself: it stores and acknowledges the CRITICAL items the sources file");
        return sources;
    }

    /** The sources raised on the urgent-alert path by a module of their own, and the file that raises each. */
    private static final Map<String, String> RAISED_BY = Map.ofEntries(
        Map.entry("SOURCE_PARTOGRAPH", "service/impl/LaborServiceImpl.java"),
        Map.entry("SOURCE_DELIVERY", "service/impl/LaborServiceImpl.java"),
        Map.entry("SOURCE_TRANSFER", "service/impl/LaborServiceImpl.java"),
        Map.entry("SOURCE_NEWBORN", "service/impl/NewbornAssessmentServiceImpl.java"),
        Map.entry("SOURCE_POSTPARTUM", "service/impl/PostpartumCareServiceImpl.java"),
        Map.entry("SOURCE_VITAL_SIGN", "service/vitals/MaternalEarlyWarningService.java"),
        Map.entry("SOURCE_NEWS2", "service/vitals/News2EscalationService.java"),
        Map.entry("SOURCE_NEWBORN_VITAL_SIGN", "service/vitals/VitalSignJudgement.java"),
        Map.entry("SOURCE_URGENT_HYPERTENSION", "service/vitals/VitalSignJudgement.java"),
        Map.entry("SOURCE_TRANSFUSION_REACTION", "service/impl/TransfusionServiceImpl.java"),
        Map.entry("SOURCE_POSITIVE_CULTURE", "service/impl/MicroCultureServiceImpl.java"),
        Map.entry("SOURCE_O_POSITIVE_COUNTERSIGN", "service/transfusion/OPositiveReleaseNotices.java"),
        Map.entry("SOURCE_INCOMPATIBLE_COUNTERSIGN", "service/transfusion/IncompatibleReleaseNotices.java"));

    private static String read(String relative) throws IOException {
        return SourceScan.stripComments(Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("every file an urgent message leaves from is listed, and is on the ladder unless it says why not")
    void everySourceOfAnUrgentMessageIsOnTheLadder() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("service"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                boolean urgentNotice = source.contains(NOTIFIES) && URGENT_TYPE.matcher(source).find();
                if (urgentNotice || source.contains(CRITICAL_ITEM)) {
                    found.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertThat(found).as("a service sends a notification of an urgent type or files a CRITICAL in-basket item: "
            + "raise it on the urgent-alert path (ObstetricAlertEscalationService.raiseAfterCommit) so it is "
            + "acknowledged or escalated, and list it in SOURCES").isEqualTo(SOURCES.keySet());

        List<String> offLadder = new ArrayList<>();
        for (Map.Entry<String, String> entry : SOURCES.entrySet()) {
            if (entry.getValue() == null && !LADDER_ENTRY.matcher(read(entry.getKey())).find()) {
                offLadder.add(entry.getKey());
            }
        }
        assertThat(offLadder).as("listed as on the ladder, but nothing in the file reaches it").isEmpty();
    }

    @Test
    @DisplayName("every source the urgent-alert path admits is raised by its module, and the path accepts it")
    void everySourceOfThePathIsRaisedSomewhere() throws Exception {
        Set<String> declared = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (Field field : ObstetricAlertEscalationService.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class
                    || !field.getName().startsWith("SOURCE_")) {
                continue;
            }
            declared.add(field.getName());
            String value = (String) field.get(null);
            if (!ObstetricAlertEscalationService.SOURCE_TYPES.contains(value)) {
                problems.add(field.getName() + " is not in SOURCE_TYPES: raiseAfterCommit would refuse it");
            }
            String raiser = RAISED_BY.get(field.getName());
            if (raiser == null) {
                continue;
            }
            String source = read(raiser);
            if (!source.contains(field.getName())
                    || !(source.contains("raiseAfterCommit(") || source.contains("raiseAlreadyNotified("))) {
                problems.add(field.getName() + " is not raised by " + raiser);
            }
        }
        assertThat(declared).as("a new source of the urgent-alert path: name the file that raises it in RAISED_BY")
            .isEqualTo(RAISED_BY.keySet());
        assertThat(problems).isEmpty();
        // The sources that are not obstetric never borrow the obstetric circle or its heading.
        assertThat(ObstetricAlertEscalationService.SOURCE_TYPES)
            .containsAll(ObstetricAlertEscalationService.GENERAL_SOURCES)
            .containsAll(ObstetricAlertEscalationService.CLOSED_BY_RECORD_ONLY)
            .containsAll(ObstetricAlertEscalationService.ONE_OPEN_PER_RECORD);
        for (String general : ObstetricAlertEscalationService.GENERAL_SOURCES) {
            assertThat(ObstetricAlertEscalationWorker.firstNoticeKey(general)).isEqualTo("urgentAlert.notification");
        }
    }

    @Test
    @DisplayName("no count of rounds ends an urgent message, and no ladder tells an administrator the alert")
    void noCapAndNoAdministratorInAnyChain() throws IOException {
        List<String> problems = new ArrayList<>();
        Pattern cap = Pattern.compile("max-rounds|maxRounds|MAX_ROUNDS|TIER_TWO");
        for (Map.Entry<String, String> entry : SOURCES.entrySet()) {
            if (entry.getValue() != null) {
                continue;
            }
            String source = read(entry.getKey());
            if (cap.matcher(source).find()) {
                problems.add(entry.getKey() + " caps its rounds or keeps a second tier of its own");
            }
            if (source.contains("ROLE_HOSPITAL_ADMIN")) {
                problems.add(entry.getKey() + " names the administrators' role: only the ladder flags them, "
                    + "through CriticalResultRecipients.administrators, with a text that carries no patient and no value");
            }
        }
        problems.addAll(capIn("service/maternity/ObstetricAlertEscalationService.java", cap));
        problems.addAll(capIn("repository/ObstetricAlertEscalationRepository.java", cap));
        assertThat(problems).isEmpty();

        // What an administrator is told: an incident is open, and for how many minutes. One argument, a number.
        MessageSource bundles = TestMessageSources.bundles();
        for (String flag : List.of(UrgentEscalationSupport.ADMIN_FLAG_KEY, "lab.critical.unresolved.admin")) {
            for (Locale locale : List.of(Locale.ENGLISH, Locale.FRENCH, Locale.forLanguageTag("es"))) {
                String text = bundles.getMessage(flag, null, locale);
                assertThat(text).as(flag + " in " + locale).contains("{0}").doesNotContain("{1}");
            }
        }
        String support = read("service/escalation/UrgentEscalationSupport.java");
        assertThat(support).as("the flag's one argument is the minutes")
            .contains("createNotification(ADMIN_FLAG_KEY, new Object[]{minutes}, administrator, ADMIN_FLAG_TYPE)");
    }

    private static List<String> capIn(String relative, Pattern cap) throws IOException {
        return cap.matcher(read(relative)).find() ? List.of(relative + " still caps the rounds") : List.of();
    }

    @Test
    @DisplayName("every source has its notice, its type and its sentence in English and in French")
    void everyTextOfTheLadderExistsInEnglishAndFrench() {
        MessageSource bundles = TestMessageSources.bundles();
        Set<String> keys = new TreeSet<>(List.of(
            "obstetric.alert.escalation", "obstetric.alert.escalation.cover", "obstetric.alert.escalation.lead",
            "obstetric.alert.escalation.documenter.cover", "obstetric.alert.escalation.documenter.lead",
            "obstetric.alert.escalation.documenter.unresolved", "urgent.alert.unresolved", UrgentEscalationSupport.ADMIN_FLAG_KEY,
            UrgentEscalationSupport.SMS_KEY, "imaging.critical.unresolved", "pro.screening.unresolved",
            "transfusion.reaction.haemovigilance.inBasketTitle", "transfusion.reaction.haemovigilance.inBasketBody",
            "clinicalRules.rule.URGENT_ALERT_ESCALATION.title", "clinicalRules.rule.URGENT_ALERT_ESCALATION.description",
            "clinicalRules.rule.URGENT_ALERT_ESCALATION.source", "clinicalRules.rule.URGENT_ALERT_ESCALATION.approver"));
        for (String source : ObstetricAlertEscalationService.SOURCE_TYPES) {
            keys.add(ObstetricAlertEscalationWorker.firstNoticeKey(source));
        }
        for (String type : UrgentAlerts.TYPES) {
            keys.add(UrgentAlerts.TYPE_PREFIX + type);
        }
        // An adult's urgent hypertension keeps the sentence of its own rule.
        for (String type : UrgentAlerts.TYPES_WITH_OWN_SENTENCE) {
            keys.add(UrgentAlerts.MESSAGE_PREFIX + type);
        }
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            for (Locale locale : List.of(Locale.ENGLISH, Locale.FRENCH)) {
                if (bundles.getMessage(key, new Object[]{"a", "b", "c", 4}, null, locale) == null) {
                    missing.add(key + " in " + locale);
                }
            }
        }
        assertThat(missing).isEmpty();
        // A French reader is not handed the English sentence.
        for (String type : UrgentAlerts.TYPES) {
            assertThat(bundles.getMessage("urgentAlert.type." + type, null, Locale.FRENCH))
                .as("urgentAlert.type." + type + " in French")
                .isNotEqualTo(bundles.getMessage("urgentAlert.type." + type, null, Locale.ENGLISH));
        }
    }

    @Test
    @DisplayName("the page that describes the ladder names every path")
    void theDocumentNamesEveryPath() throws IOException {
        assertThat(DOC).as("docs/security/urgent-message-escalation.md").exists();
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        for (String path : List.of("CriticalValueNotificationService", "ImagingCriticalNotificationService",
                "ProScreeningEscalationService", "ObstetricAlertEscalationService", "UrgentEscalationSupport",
                "OPositiveReleaseNotices", "IncompatibleReleaseNotices", "UrgentClinicalMessageGuardTest")) {
            assertThat(doc).as("the page names " + path).contains(path);
        }
    }
}
