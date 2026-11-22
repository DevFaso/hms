package com.example.hms.architecture;

import com.example.hms.i18n.TestMessageSources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3301 (r70, privacy): every refill request rang the bell of the doctors AND nurses of the prescriber's
 * department with the medicine's name; an antiretroviral was named to a nurse who was not the prescriber. A
 * medicine's name is, more often than not, the diagnosis.
 *
 * <p>The class: a notification, an e-mail, an SMS or an in-basket item about a prescription names its medicine only
 * to someone who must act on that very medicine. This guard keeps the list of every source file that both reads a
 * medicine's name and writes such a message, with who reads the name there:
 * <ul>
 *   <li>{@link Audience#PRESCRIBER}: the prescriber of the prescription;</li>
 *   <li>{@link Audience#DISPENSING_PHARMACY}: the pharmacy that dispenses it;</li>
 *   <li>{@link Audience#PATIENT}: the patient it was prescribed to;</li>
 *   <li>{@link Audience#REVIEWING_CLINICIAN}: a clinician asked to review that very order (a second review of a
 *       dose, an allergy conflict): the task is the review of the medicine, so it names it;</li>
 *   <li>{@link Audience#NOBODY}: the file reads a medicine's name for something else, and no message it writes
 *       carries it (its own tests hold that).</li>
 * </ul>
 * A new file that does both fails here until someone decides who reads the name. The renewal announcement and the
 * deferral tasks, which are read by people who are none of these, are checked on their texts too.
 */
@DisplayName("D3301: a message about a prescription names its medicine only to who must act on it")
class PrescriptionNotificationPrivacyGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** A medicine's name being read. */
    private static final Pattern READS_MEDICINE =
        Pattern.compile("getMedicationName\\(\\)|getMedicationDisplayName\\(\\)|\\bmedicationName\\b");

    /** A message being written to somebody: a bell, an in-basket item, an inbox row, a mail, an SMS. */
    private static final Pattern WRITES_MESSAGE = Pattern.compile(
        "createNotification\\(|createLiteralNotification\\(|CreateInBasketItemRequest|ClinicalInboxItemDTO\\.builder"
            + "|NotificationText\\.of\\(|emailService\\.|smsService\\.|\\.sendSms\\(|PartnerSmsTemplates");

    enum Audience { PRESCRIBER, DISPENSING_PHARMACY, PATIENT, REVIEWING_CLINICIAN, NOBODY }

    private record Reader(Audience audience, String why) {
    }

    private static final Map<String, Reader> READERS = Map.ofEntries(
        Map.entry("service/EncounterServiceImpl.java", new Reader(Audience.PATIENT,
            "the patient's own visit summary (bell and mail to the patient)")),
        Map.entry("service/PrescriptionServiceImpl.java", new Reader(Audience.REVIEWING_CLINICIAN,
            "the urgent dose review task of a pharmacist or a physician: the review of that medicine")),
        Map.entry("service/ResultReviewServiceImpl.java", new Reader(Audience.PRESCRIBER,
            "the prescriber's own inbox rows (refill requests of their own prescriptions)")),
        Map.entry("service/allergy/CurrentAllergyRecheck.java", new Reader(Audience.REVIEWING_CLINICIAN,
            "the allergy conflict notice to the prescriber and the clinicians who must review that order")),
        Map.entry("service/impl/OrderReviewHoldServiceImpl.java", new Reader(Audience.NOBODY,
            "the held-order notice names the patient only; the medicine's name is read for the hold's record")),
        Map.entry("service/impl/PatientPortalServiceImpl.java", new Reader(Audience.NOBODY,
            "QA D3301: the renewal announcement goes to the prescriber alone and names the patient only")),
        Map.entry("service/impl/PrescriptionSmsDispatchServiceImpl.java", new Reader(Audience.DISPENSING_PHARMACY,
            "the offer sent to the partner pharmacy that will dispense, and the patient's own SMS")),
        Map.entry("service/impl/RefillApprovalServiceImpl.java", new Reader(Audience.PRESCRIBER,
            "the pharmacy's flag to the prescriber (a cover reads « the prescription »), the decision to the patient")),
        Map.entry("service/pharmacy/DispenseBillingDeferralFollowUp.java", new Reader(Audience.NOBODY,
            "QA D3301: billing and finance are told of a hand-over to a patient, never of the medicine")),
        Map.entry("service/pharmacy/PrescriberPharmacyNotificationWriter.java", new Reader(Audience.PRESCRIBER,
            "what the pharmacy tells the prescriber about their own prescription")),
        Map.entry("service/pharmacy/partner/PartnerSmsTemplates.java", new Reader(Audience.DISPENSING_PHARMACY,
            "the texts of the offer to the partner pharmacy that will dispense")),
        Map.entry("service/pharmacy/partner/SmsPartnerNotificationChannel.java", new Reader(Audience.DISPENSING_PHARMACY,
            "the offer to the partner pharmacy that will dispense")));

    @Test
    @DisplayName("every file that reads a medicine's name and writes a message says who reads the name")
    void everyWriterIsClassified() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        try (var files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (READS_MEDICINE.matcher(text).find() && WRITES_MESSAGE.matcher(text).find()) {
                    found.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertThat(found).as("the scan found the known writers").contains("service/impl/PatientPortalServiceImpl.java");
        TreeSet<String> unclassified = new TreeSet<>(found);
        unclassified.removeAll(READERS.keySet());
        assertThat(unclassified)
            .as("a new file reads a medicine's name and writes a notification, a mail, an SMS or an in-basket item: "
                + "decide who reads the name (the prescriber, the dispensing pharmacy, the patient, a reviewing "
                + "clinician, or nobody) and list it in READERS")
            .isEmpty();
        TreeSet<String> stale = new TreeSet<>(READERS.keySet());
        stale.removeAll(found);
        assertThat(stale).as("READERS entries whose file no longer does both: remove them").isEmpty();
        assertThat(READERS.values()).allSatisfy(reader -> assertThat(reader.why()).isNotBlank());
    }

    @Test
    @DisplayName("the renewal announcement is written for one reader and carries the patient's name only")
    void theRenewalAnnouncementNamesThePatientOnly() throws IOException {
        String portal = Files.readString(MAIN.resolve("service/impl/PatientPortalServiceImpl.java"),
            StandardCharsets.UTF_8);
        int start = portal.indexOf("private void notifyCareTeamForRefillRequest(");
        int end = portal.indexOf("private void dispatchRefillInAppNotifications(");
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(start);
        String routing = portal.substring(start, end);
        assertThat(routing).contains("new Object[]{patientName}")
            .contains("renewalAlertRouting.answererOf(prescription)")
            .doesNotContain("getMedicationName")
            .doesNotContain("findActiveProvidersByHospitalAndDepartment");
        // The department's providers are still read to BOOK an appointment; never to announce a renewal.
        assertThat(portal.substring(end)).doesNotContain("\"refill.request.notification\", new Object[]{patientName,");
    }

    @Test
    @DisplayName("the texts read by people who cannot act on the medicine have no place for its name, in any language")
    void theTextsHaveNoPlaceForTheMedicine() {
        MessageSource bundles = TestMessageSources.bundles();
        Map<String, List<String>> problems = new TreeMap<>();
        for (String language : List.of("en", "fr", "es")) {
            Locale locale = Locale.forLanguageTag(language);
            // One argument, the patient: {0}. A second placeholder is where the medicine used to be.
            for (String key : List.of("refill.request.notification", "refill.request.email.body")) {
                String text = bundles.getMessage(key, null, null, locale);
                if (text == null || !text.contains("{0}") || text.contains("{1}")) {
                    problems.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(language);
                }
            }
            // The patient is {1}; {0} was the medicine and is no longer read.
            for (String key : List.of("pharmacy.deferral.followUp.reviewBody",
                "pharmacy.deferral.followUp.approvalBody", "pharmacy.deferral.followUp.creditRequested")) {
                String text = bundles.getMessage(key, null, null, locale);
                if (text == null || !text.contains("{1}") || text.contains("{0}")) {
                    problems.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(language);
                }
            }
        }
        assertThat(problems).as("texts that still have a place for the medicine's name").isEmpty();
    }
}
