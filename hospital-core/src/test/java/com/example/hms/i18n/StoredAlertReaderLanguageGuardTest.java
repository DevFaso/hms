package com.example.hms.i18n;

import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.enums.LiquorColour;
import com.example.hms.model.labor.LaborPartographEntry;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.maternity.LaborAlertArgs;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1427: an obstetric alert's sentence is stored in the staff language; the
 * response handed that copy to the reader, so the red toast was French in a
 * Spanish session while the in-basket about the same alert was right. A mapper
 * never hands the stored copy on as is, and every class that writes text in the
 * staff language says who reads it.
 */
@DisplayName("D1427: text stored in the staff language is not what a reader of another language reads")
class StoredAlertReaderLanguageGuardTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/hms");
    private static final String LABOR_PREFIX = "labor.alert.msg.";

    private static final Pattern STAFF_LOCALE = Pattern.compile("NotificationLocales\\s*+\\.\\s*+STAFF\\b");
    private static final Pattern RAW_ALERT_MESSAGE = Pattern.compile("\\.message\\(\\s*+alert\\.getMessage\\(\\)\\s*+\\)");

    private static final String NOTICE = "a notice's staff-language copy; the reader's is rendered from its key";
    private static final String STORED_RECORD =
        "a written record in the hospital's language, joined with what the user typed or printed on a document";
    private static final String ALERT = "the stored copy of an alert; the mapper renders the reader's from its code";

    /** Every class writing staff-language text, and who reads it. */
    private static final Map<String, String> KNOWN = Map.ofEntries(
        Map.entry("service/billing/BedDaysChargeSource.java", STORED_RECORD),
        Map.entry("service/billing/ConsultationChargeSource.java", STORED_RECORD),
        Map.entry("service/billing/EncounterCheckoutChargeListener.java", STORED_RECORD),
        Map.entry("service/billing/TransfusionChargeSource.java", STORED_RECORD),
        Map.entry("service/CriticalValueNotificationService.java", "an SMS and a notice's copy: the recipient's phone has no session"),
        Map.entry("service/ImagingCriticalNotificationService.java", "an SMS: the recipient's phone has no session"),
        Map.entry("service/impl/LaborServiceImpl.java", ALERT + "; the override note joins what the user typed"),
        Map.entry("service/impl/NewbornAssessmentServiceImpl.java", ALERT),
        Map.entry("service/maternity/NewbornRegistrationService.java", STORED_RECORD
            + ": the baby's provisional name and contact relationship (QA D1592), which the desk edits"),
        Map.entry("service/impl/PostpartumCareServiceImpl.java", ALERT + "; the escalation reason is never returned"),
        Map.entry("service/impl/PatientPortalServiceImpl.java", "the refill e-mail to staff; the bell is keyed (D665)"),
        Map.entry("service/impl/TransfusionServiceImpl.java", NOTICE),
        Map.entry("service/impl/DeathDisputeServiceImpl.java", NOTICE),
        // QA D1802: the reconciliation task of a contradicted stillbirth certificate; the reader's copy is keyed.
        Map.entry("service/maternity/StillbirthCertificateConflicts.java", NOTICE),
        // QA D1516: the note on an appointment a death entered in error had cancelled, beside the closure's own.
        Map.entry("service/impl/MortalityServiceImpl.java", STORED_RECORD),
        Map.entry("service/impl/OrderReviewAfterCommit.java", NOTICE),
        Map.entry("service/lab/LabResultReleaseNotifier.java", NOTICE),
        Map.entry("service/lab/LabSpecimenRejectionNotifier.java", NOTICE),
        Map.entry("service/LabResultServiceImpl.java", NOTICE),
        Map.entry("service/PrescriptionServiceImpl.java", NOTICE),
        Map.entry("service/allergy/CurrentAllergyRecheck.java", NOTICE),
        Map.entry("service/UnsignedNoteFollowUp.java", NOTICE),
        Map.entry("service/maternity/ObstetricAlertEscalationWorker.java", NOTICE),
        Map.entry("service/NotificationServiceImpl.java", NOTICE),
        Map.entry("service/pro/ProScreeningEscalationService.java", "an SMS: the recipient's phone has no session"));

    @Test
    @DisplayName("every class writing staff-language text is listed with who reads it")
    void everyStaffLanguageWriterIsListed() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = ROOT.relativize(file).toString().replace('\\', '/');
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (rel.equals("service/i18n/NotificationLocales.java")) {
                    continue;
                }
                if (STAFF_LOCALE.matcher(source).find()) {
                    found.add(rel);
                    if (!KNOWN.containsKey(rel)) {
                        problems.add(rel + " writes staff-language text: is it returned to a reader of another language?");
                    }
                }
                if (rel.startsWith("mapper/") && RAW_ALERT_MESSAGE.matcher(source).find()) {
                    problems.add(rel + " hands a stored alert sentence to the reader (ObstetricAlertText)");
                }
            }
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
        assertThat(found).as("a listed class that no longer writes staff-language text comes off the list")
            .containsExactlyInAnyOrderElementsOf(KNOWN.keySet());
    }

    @Test
    @DisplayName("every labour alert of the bundle can be rendered again from its row, in EN, FR and ES")
    void everyLabourAlertCodeHasItsArguments() throws IOException {
        Properties english = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("i18n/messages-maternity.properties")) {
            assertThat(in).isNotNull();
            english.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> codes = english.stringPropertyNames().stream()
            .filter(k -> k.startsWith(LABOR_PREFIX))
            .map(k -> k.substring(LABOR_PREFIX.length()))
            .sorted()
            .toList();
        assertThat(codes).as("the scan found the alerts").hasSizeGreaterThanOrEqualTo(10);

        MessageSource messages = TestMessageSources.bundles();
        NotificationLocalizer localizer = new NotificationLocalizer(messages);
        LocalDateTime observed = LocalDateTime.of(2026, 11, 6, 12, 0);
        LaborPartographEntry entry = new LaborPartographEntry();
        entry.setFetalHeartRateBpm(90);
        entry.setLiquorColour(LiquorColour.MECONIUM_STAINED);
        entry.setCervicalDilationCm(5);
        entry.setObservationTime(observed);
        entry.setTemperatureCelsius(38.5);
        entry.setPulseBpm(130);
        entry.setSystolicBpMmHg(170);
        entry.setDiastolicBpMmHg(115);
        DeliveryRecord delivery = new DeliveryRecord();
        delivery.setGestationalAgeWeeksAtBirth(30);
        delivery.setBirthWeightGrams(3200);
        delivery.setEstimatedBloodLossMl(700);
        delivery.setApgarFiveMinute(3);

        for (String code : codes) {
            Optional<List<Object>> args = LaborAlertArgs.ofEntry(code, entry, observed.minusHours(8))
                .or(() -> LaborAlertArgs.ofDelivery(code, delivery));
            assertThat(args).as("%s has no way back to its arguments", code).isPresent();
            for (Locale locale : List.of(Locale.ENGLISH, Locale.FRENCH, Locale.forLanguageTag("es"))) {
                assertThat(localizer.render(LABOR_PREFIX + code, args.orElseThrow(), locale))
                    .as("%s in %s", code, locale)
                    .isNotBlank()
                    .doesNotContain("{0", "{1", "null");
            }
        }
    }
}
