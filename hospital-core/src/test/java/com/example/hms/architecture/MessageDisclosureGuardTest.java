package com.example.hms.architecture;

import com.example.hms.service.notification.SensitiveWording;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scorecard K4: messages, attachments and links disclose the minimum. An SMS is read on a shared
 * phone and kept on a lock screen; an e-mail is forwarded and previewed. So:
 * <ul>
 *   <li>every outgoing message template (SMS, e-mail, verification code) of every bundle and locale
 *       is listed in {@code docs/security/message-disclosure-inventory.md} with its fields and
 *       verdict: a new template fails here until it is reviewed and listed;</li>
 *   <li>no template's own words reveal a protected category ({@link SensitiveWording}: HIV, STI,
 *       pregnancy, mental health, substance use, sexual violence), in any locale;</li>
 *   <li>every SMS takes at most the arguments it was reviewed with ({@link #SMS_ARGUMENTS}): a new
 *       {@code {n}}, the way a medicine or a test name gets in, fails until reviewed;</li>
 *   <li>the code that writes an SMS reads no clinical value and no full name;</li>
 *   <li>the invoice mailed to a patient is the minimum-disclosure copy.</li>
 * </ul>
 */
@DisplayName("Messages, attachments and links disclose the minimum (K4)")
class MessageDisclosureGuardTest {

    private static final Path BUNDLES = Path.of("src/main/resources/i18n");
    private static final Path INVENTORY = Path.of("../docs/security/message-disclosure-inventory.md");
    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");

    /** The keys that are the text of a message leaving the system (SMS, e-mail, verification code). */
    private static final Pattern OUTGOING = Pattern.compile(
        "^(?:sms\\.|email\\.|recipientVerification\\.|encounter\\.visitSummary\\.email\\.|refill\\.request\\.email\\.)");

    /** An inventory entry: a key or a key prefix ending in {@code .*}, in backticks. */
    private static final Pattern INVENTORY_ENTRY = Pattern.compile("`((?:sms|email|recipientVerification|encounter"
        + "\\.visitSummary\\.email|refill\\.request\\.email)\\.[A-Za-z0-9_.]*(?:\\*)?)`");

    /**
     * The highest {@code {n}} each SMS was reviewed with (-1: none), in any locale. Only a reviewer adds a key or
     * raises a number, after checking what the new argument carries: a first name, a date, a time, a hospital or
     * pharmacy, a code, a link to the signed-in portal, initials. Never a medicine, a test, a value, a diagnosis
     * or a full name (the partner pharmacy's offer names the medicine to the pharmacy that must dispense it).
     */
    private static final Map<String, Integer> SMS_ARGUMENTS = new TreeMap<>(Map.ofEntries(
        Map.entry("sms.activation.hospital", 2),
        Map.entry("sms.activation.patient", 2),
        Map.entry("sms.activation.registrar", 3),
        Map.entry("sms.activation.registrar.platform", 2),
        Map.entry("sms.activation.staff", 3),
        Map.entry("sms.activation.staff.platform", 2),
        Map.entry("sms.appointment.confirmation", 2),
        Map.entry("sms.appointment.reminder", 2),
        Map.entry("sms.message.required", -1),
        // QA D3865: the patient's known allergies, and an override of one with the prescriber's reason (cut to
        // 120 characters), to the partner pharmacy that dispenses the order: it must know what it is dispensing over.
        Map.entry("sms.partner.allergies", 0),
        Map.entry("sms.partner.allergyOverride", 1),
        Map.entry("sms.partner.autoRejected", 0),
        Map.entry("sms.partner.heldForReview", 0),
        Map.entry("sms.partner.medicationFallback", -1),
        Map.entry("sms.partner.offer", 2),
        Map.entry("sms.partner.offer.partial", 3),
        Map.entry("sms.partner.patientAccepted", 0),
        Map.entry("sms.partner.patientDispensed", 0),
        Map.entry("sms.partner.pharmacyFallback", -1),
        Map.entry("sms.partner.reminder", 0),
        Map.entry("sms.partner.superseded", 0),
        Map.entry("sms.partner.withdrawn", 0),
        Map.entry("sms.passwordReset.changed", -1),
        Map.entry("sms.passwordReset.link", 0),
        Map.entry("sms.pharmacy.dispensed", 1),
        Map.entry("sms.pharmacy.outOfStock", 1),
        Map.entry("sms.pharmacy.outOfStock.backorder", -1),
        Map.entry("sms.pharmacy.outOfStock.backorder.dated", 0),
        Map.entry("sms.pharmacy.outOfStock.partner", 0),
        Map.entry("sms.pharmacy.outOfStock.print", -1),
        Map.entry("sms.pharmacy.readyCancelled", 1),
        Map.entry("sms.pharmacy.readyForCollection", 1),
        Map.entry("sms.pharmacy.readyReminder", 1),
        Map.entry("sms.pharmacy.refillReminder", 1),
        Map.entry("sms.phoneVerification.code", 1),
        Map.entry("sms.provider.authFailed", -1),
        Map.entry("sms.provider.rejected", -1),
        Map.entry("sms.provider.unknownOutcome", -1),
        Map.entry("sms.provider.unreachable", -1),
        Map.entry("sms.recall.due", 0),
        Map.entry("sms.recipient.notAllowlisted", -1),
        Map.entry("sms.recipient.notSupported", -1),
        Map.entry("sms.result.available", 0),
        Map.entry("sms.staff.criticalImaging", 0),
        Map.entry("sms.staff.criticalImaging.escalation", 0),
        Map.entry("sms.staff.criticalLab", 0),
        Map.entry("sms.staff.criticalLab.escalation", 0),
        Map.entry("sms.staff.questionnaire", 0),
        Map.entry("sms.staff.questionnaire.escalation", 0),
        Map.entry("sms.username.reminder", 0),
        Map.entry("sms.waitlist.offer", 3),
        Map.entry("recipientVerification.code", 0)));

    /**
     * A staff member's own role, named in their account e-mails (« Sage-femme »): not patient information, so the
     * only outgoing text the sensitive-word scan does not read.
     */
    private static final String STAFF_ROLE_LABELS = "email.role.label.";

    /** What an SMS-writing method must not read: a clinical value or the full name. */
    private static final Pattern CLINICAL_READ = Pattern.compile("\\.(?:getFullName|getMedicationName|"
        + "getLabTestDefinition|getResultValue|getStudyType|getReportTitle|getInstrument|getImpression|getDiagnosis)"
        + "\\s*+\\(");

    /** The methods that compose a clinician's critical SMS ({file, method}). */
    private static final List<String[]> STAFF_SMS_WRITERS = List.of(
        new String[] {"CriticalValueNotificationService.java", "smsText"},
        new String[] {"ImagingCriticalNotificationService.java", "sendSmsBestEffort"},
        new String[] {"ImagingCriticalNotificationService.java", "smsInitials"},
        new String[] {"pro/ProScreeningEscalationService.java", "smsText"});

    @Test
    @DisplayName("every outgoing message template is in the disclosure inventory, and every entry still exists")
    void everyTemplateIsInventoried() throws IOException {
        Set<String> entries = inventoryEntries();
        Set<String> keys = new TreeSet<>();
        for (Path bundle : bundles()) {
            for (String key : load(bundle).stringPropertyNames()) {
                if (OUTGOING.matcher(key).find()) {
                    keys.add(key);
                }
            }
        }
        assertThat(keys).as("outgoing message keys found").hasSizeGreaterThan(150);
        List<String> missing = keys.stream().filter(key -> entries.stream().noneMatch(e -> covers(e, key))).toList();
        assertThat(missing).as("an outgoing message template that is not in docs/security/message-disclosure-"
            + "inventory.md: review what it discloses (the K4 rule there) and list it with its fields and verdict")
            .isEmpty();
        List<String> broad = entries.stream().filter(e -> e.endsWith(".*") && !familyWildcard(e)).toList();
        assertThat(broad).as("a wildcard entry must name one message family under its root (`email.invoice.*`), "
            + "never a whole namespace (`email.*`, `encounter.visitSummary.email.*`): list those keys one by one")
            .isEmpty();
        List<String> stale = entries.stream().filter(e -> keys.stream().noneMatch(key -> covers(e, key))).toList();
        assertThat(stale).as("an inventory entry that names no template any more: remove it").isEmpty();
    }

    @Test
    @DisplayName("no outgoing template's own words reveal a protected category, in any locale")
    void noTemplateNamesASensitiveCategory() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path bundle : bundles()) {
            Properties properties = load(bundle);
            for (String key : properties.stringPropertyNames()) {
                if (!OUTGOING.matcher(key).find() || key.startsWith(STAFF_ROLE_LABELS)) {
                    continue;
                }
                String hit = SensitiveWording.firstMatch(properties.getProperty(key));
                if (hit != null) {
                    offenders.add(bundle.getFileName() + " " + key + " (« " + hit + " »)");
                }
            }
        }
        assertThat(offenders).as("a message template names a protected category (HIV, STI, pregnancy, mental "
            + "health, substance use, sexual violence): a message says that something is available and where, "
            + "never what").isEmpty();
    }

    @Test
    @DisplayName("every SMS takes only the arguments it was reviewed with")
    void smsArgumentsAreFrozen() throws IOException {
        Pattern placeholder = Pattern.compile("\\{(\\d+)");
        List<String> offenders = new ArrayList<>();
        for (Path bundle : bundles()) {
            Properties properties = load(bundle);
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith("sms.") && !key.equals("recipientVerification.code")) {
                    continue;
                }
                Integer reviewed = SMS_ARGUMENTS.get(key);
                if (reviewed == null) {
                    offenders.add(bundle.getFileName() + " " + key + " is a new SMS");
                    continue;
                }
                Matcher m = placeholder.matcher(properties.getProperty(key));
                while (m.find()) {
                    if (Integer.parseInt(m.group(1)) > reviewed) {
                        offenders.add(bundle.getFileName() + " " + key + " takes {" + m.group(1) + "}");
                    }
                }
            }
        }
        assertThat(offenders).as("an SMS with an argument nobody reviewed: a medicine, a test, a value or a full "
            + "name must never reach a text message. Check what the argument carries, then record it in "
            + "SMS_ARGUMENTS and in the inventory").isEmpty();
    }

    @Test
    @DisplayName("the code that writes a patient's or a clinician's SMS reads no clinical value and no full name")
    void smsWritersReadNoClinicalValue() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String[] writer : STAFF_SMS_WRITERS) {
            String source = SourceScan.stripComments(Files.readString(SERVICES.resolve(writer[0]),
                StandardCharsets.UTF_8));
            String body = SourceScan.methodBody(source, writer[1]);
            if (body == null) {
                offenders.add(writer[0] + "#" + writer[1] + " is gone (update STAFF_SMS_WRITERS)");
            } else if (CLINICAL_READ.matcher(body).find()) {
                offenders.add(writer[0] + "#" + writer[1]);
            }
        }
        // The pharmacy SMS: the first name and the pharmacy, never the medicine.
        String pharmacy = SourceScan.stripComments(Files.readString(
            SERVICES.resolve("pharmacy/PharmacyServiceSupport.java"), StandardCharsets.UTF_8));
        if (CLINICAL_READ.matcher(pharmacy).find() || pharmacy.contains("medicationName")) {
            offenders.add("pharmacy/PharmacyServiceSupport.java (the patient's pharmacy SMS)");
        }
        // The patient's ledger notices take the hospital, a date and a time (PatientNotificationRoutingGuardTest).
        String renderer = SourceScan.stripComments(Files.readString(
            SERVICES.resolve("notification/PatientNotificationRenderer.java"), StandardCharsets.UTF_8));
        if (CLINICAL_READ.matcher(renderer).find()) {
            offenders.add("notification/PatientNotificationRenderer.java");
        }
        assertThat(offenders).as("an SMS writer reads a clinical value or the full name: an SMS carries the "
            + "patient's initials or first name and a neutral event, the rest stays behind the sign-in").isEmpty();
    }

    @Test
    @DisplayName("the invoice mailed to a patient is the minimum-disclosure copy")
    void theMailedInvoiceIsTheMinimumCopy() throws IOException {
        String source = SourceScan.stripComments(Files.readString(SERVICES.resolve("InvoiceEmailService.java"),
            StandardCharsets.UTF_8));
        assertThat(source).as("InvoiceEmailService attaches generateMailedInvoicePdf (no test or medicine "
                + "names), never the desk copy")
            .contains("generateMailedInvoicePdf(").doesNotContain("generateInvoicePdf(");
    }

    @Test
    @DisplayName("the sensitive lexicon matches whole abbreviations and stems, ignoring case and accents")
    void lexiconBehaves() {
        assertThat(SensitiveWording.reveals("Sérologie VIH")).isTrue();
        assertThat(SensitiveWording.reveals("Test de GROSSESSE")).isTrue();
        assertThat(SensitiveWording.reveals("Échographie obstétricale")).isTrue();
        assertThat(SensitiveWording.reveals("Consultation psychiatrique")).isTrue();
        assertThat(SensitiveWording.reveals("Dépistage IST")).isTrue();
        assertThat(SensitiveWording.reveals("Consultation sage-femme")).isTrue();
        assertThat(SensitiveWording.reveals("Caryotype")).isTrue();
        assertThat(SensitiveWording.reveals("Test de paternité")).isTrue();
        assertThat(SensitiveWording.reveals("Électrophorèse de l'hémoglobine")).isTrue();
        assertThat(SensitiveWording.reveals("Drépanocytose")).isTrue();
        assertThat(SensitiveWording.reveals("GeneXpert")).isTrue();
        assertThat(SensitiveWording.reveals("Recherche de BK")).isTrue();
        assertThat(SensitiveWording.reveals("Planning familial")).isTrue();
        assertThat(SensitiveWording.reveals("Gynécologie")).isTrue();
        assertThat(SensitiveWording.reveals("Échographie pelvienne")).isTrue();
        assertThat(SensitiveWording.reveals("Soins néonatals")).isTrue();
        assertThat(SensitiveWording.reveals("Liste des rendez-vous")).isFalse();
        assertThat(SensitiveWording.reveals("Pansement violet")).isFalse();
        assertThat(SensitiveWording.reveals("Consultation")).isFalse();
        assertThat(SensitiveWording.reveals(null)).isFalse();
    }

    /** A wildcard that names a family under one of the {@link #OUTGOING} roots, not the root itself. */
    private static boolean familyWildcard(String entry) {
        String prefix = entry.substring(0, entry.length() - 1); // keeps the trailing dot
        Matcher root = OUTGOING.matcher(prefix);
        return root.find() && prefix.length() > root.end() + 1;
    }

    private static boolean covers(String entry, String key) {
        return entry.endsWith(".*") ? key.startsWith(entry.substring(0, entry.length() - 1)) : entry.equals(key);
    }

    private static Set<String> inventoryEntries() throws IOException {
        assertThat(INVENTORY).as("docs/security/message-disclosure-inventory.md").exists();
        Set<String> entries = new TreeSet<>();
        Matcher m = INVENTORY_ENTRY.matcher(Files.readString(INVENTORY, StandardCharsets.UTF_8));
        while (m.find()) {
            entries.add(m.group(1));
        }
        return entries;
    }

    private static List<Path> bundles() throws IOException {
        try (Stream<Path> files = Files.list(BUNDLES)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".properties")).sorted().toList();
        }
    }

    private static Properties load(Path bundle) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
