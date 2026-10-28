package com.example.hms.i18n;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D952, D989, D944, D1000, D958 guard: text the backend STORES or returns
 * as data, composed from English literals.
 *
 * <p>A triage pain score is written into the vitals row as "Pain scale: 8";
 * a nursing intake is appended to the encounter notes under
 * "--- Nursing Intake ---" and "[Medication Reconciliation] 2 medication(s)
 * reported"; a walk-in's reason falls back to "Walk-in"; a stock-out routing
 * reason is "Medication out of stock; placed on back order"; a timeline row's
 * title falls back to "Lab result". None of these go through an exception or
 * a response wrapper, so neither {@link ExceptionProseRatchetTest} nor
 * {@link ResponseProseGuardTest} sees them, and every French or Spanish
 * clinician reads them in English — forever, because they are persisted.
 *
 * <p>The scan reads the services and mappers (comments stripped) for a
 * text-bearing setter or builder step ({@code notes(…)}, {@code setReason(…)},
 * {@code .title(…)}, {@code .append(…)}, …) whose argument contains an English
 * prose literal, for a Capitalised single-word fallback ({@code : "Walk-in"}),
 * and for a section-header literal ({@code "[Nursing Assessment]"},
 * {@code "--- Nursing Intake ---"}) anywhere. A sink whose receiver chain is a
 * log call ({@code log.}, {@code LOG.}, {@code logger.}) or an audit builder
 * ({@code AuditEvent…}, {@code .eventDescription(}, {@code .auditDescription(})
 * is exempt by house decision (the audit log is English). HTML e-mail markup
 * is exempt (its text goes through the mail templates' own keys).
 *
 * <p>Each entry below is {@code file::literal} with the number of places it is
 * written; a literal not listed, or written more times than listed, fails the
 * build — resolve a key in the request locale with {@code MessageUtil}, or
 * store a code the portal labels. Lower a count when a site is converted.
 */
@DisplayName("Stored and returned text is composed from keys, not English literals")
class StoredTextProseGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    /**
     * Everything that can write a row: services and mappers, but also controllers
     * (the HL7 inbound adapter composes {@code .notes("Imported via HL7v2 …")}), the
     * HL7/FHIR/event/bootstrap packages. Only the exception and config packages are
     * left out: nothing there reaches a text column.
     */
    private static final Pattern SCANNED_DIR = Pattern.compile(
        "^(?!exception/|config/|bootstrap/DevSyntheticDataSeeder\\.java).*\\.java$");

    /**
     * Text-bearing setters and builder steps. {@code .body(} is a RESPONSE sink and
     * belongs to {@link ResponseProseGuardTest}; {@code setBody(} (a notification or
     * e-mail body) stays here.
     */
    static final Pattern TEXT_SINK = Pattern.compile(
        "\\.(notes|setNotes|clinicalNotes|setClinicalNotes|reason|setReason|description|setDescription|title|setTitle"
            + "|chiefComplaint|setChiefComplaint|appointmentReason|setAppointmentReason|summary|setSummary|message"
            + "|setMessage|setBody|comment|setComment|comments|setComments|instructions|setInstructions|append"
            + "|label|setLabel|text|setText|content|setContent|subject|setSubject)\\s*\\(");

    /** {@code "[Word Word]"} or {@code "--- Word ---"}: a section header written into a note. */
    static final Pattern SECTION_HEADER = Pattern.compile(
        "\"(?:\\\\n)*(?:\\[[A-Z][a-z]+(?: [A-Z][a-z]+)*\\]|-{2,} [A-Za-z][^\"]*? -{2,})(?:\\\\n)*[ :]*\"");

    /**
     * QA D944: a Capitalised single word as the else-branch of a text sink
     * ({@code … ? complaint : "Walk-in"}) is a label the user reads, not a code.
     */
    static final Pattern FALLBACK_WORD = Pattern.compile(":\\s*\"([A-Z][a-z]+(?:-[a-z]+)*)\"\\s*$");

    static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * A receiver chain that is a log call or an audit builder: exempt (the audit
     * log is English by house decision). Matched as calls and type names, not as
     * substrings, so a variable merely named {@code eventDescription} exempts
     * nothing; a notification's title or message is user-facing stored text and
     * is NOT exempt.
     */
    private static final Pattern EXEMPT_RECEIVER = Pattern.compile(
        "\\b(?:log|LOG|logger)\\.|\\bAuditEvent\\w*\\b|\\.(?:eventDescription|auditDescription)\\(");

    static boolean isProse(String literal) {
        if (literal.indexOf(' ') < 0 || literal.startsWith("<") || literal.contains("style=")) {
            return false;
        }
        // Escape sequences and a plural "(s)" are not part of the words.
        String plain = literal.replaceAll("\\\\[ntr]", " ").replaceAll("\\((?:s|es)\\)", "");
        int words = 0;
        for (String token : plain.split("\\s+")) {
            if (token.matches("[\\[(]?[A-Za-zÀ-ÿ]{2,}[\\])]?[.,;:!?]*")) {
                words++;
            }
        }
        return words >= 2;
    }

    private record Allowance(int count, String reason) { }

    private static final String D952 = "QA D952: nursing intake written into encounter notes with English headers";
    private static final String D989 = "QA D989: triage pain score stored as English \"Pain scale: 8\"";
    private static final String D1000 = "QA D1000: English routing reasons in the FR/ES pharmacy UI";
    private static final String D958 = "QA D958: timeline/in-basket title falls back to the English \"Lab result\"";
    private static final String D944 = "QA D944: the walk-in's reason falls back to the English \"Walk-in\" in the queue";
    private static final String PRE_EXISTING = "pre-existing English stored text (class of QA D952/D989); key it with the next touch";
    private static final String CDS_CARD =
        "QA D935/D953 class: CDS card text composed in English (the \"HMS PEDIATRIC DOSE CHECK\" source label shape); key it";
    private static final String FHIR_DISPLAY =
        "FHIR `display` text; English by the coding systems' convention, read by integrators not by clinicians — pinned, not a defect";
    private static final String SEEDED_DESCRIPTION =
        "QA D941 class: a description seeded in English and shown in the admin UI; a later migration re-keys it (never edit the applied one)";

    private static Map<String, Allowance> allow(Object... siteCountReason) {
        Map<String, Allowance> out = new TreeMap<>();
        for (int i = 0; i < siteCountReason.length; i += 3) {
            out.put((String) siteCountReason[i], new Allowance((Integer) siteCountReason[i + 1], (String) siteCountReason[i + 2]));
        }
        return out;
    }

    /** Sites as they stand: {@code file::literal}, how many places write it, why. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = allow(
        "service/ClinicalDashboardServiceImpl.java::Appointments Today", 1, PRE_EXISTING,
        "service/ClinicalDashboardServiceImpl.java::Pending Results", 1, PRE_EXISTING,
        "service/ClinicalDashboardServiceImpl.java::Active Patients", 1, PRE_EXISTING,
        "service/ClinicalDashboardServiceImpl.java::Refill Requests", 1, PRE_EXISTING,
        "service/DepartmentServiceImpl.java::Auto-provisioned hospital admin assignment for department creation", 1, PRE_EXISTING,
        "service/EncounterServiceImpl.java::Pain scale: ", 1, D989,
        "service/EncounterServiceImpl.java::[Nursing Assessment]", 1, D952,
        "service/EncounterServiceImpl.java::[Pain Assessment]", 1, D952,
        "service/EncounterServiceImpl.java::[Fall Risk]", 1, D952,
        "service/EncounterServiceImpl.java::[Medication Reconciliation] ", 1, D952,
        "service/EncounterServiceImpl.java:: medication(s) reported:\\n", 1, D952,
        "service/EncounterServiceImpl.java::\\n\\n--- Nursing Intake ---\\n", 1, D952,
        "service/EncounterServiceImpl.java::Unknown", 1, D952 + " (medication name fallback)",
        "service/LabResultServiceImpl.java::Lab result", 1, D958,
        "service/ReceptionServiceImpl.java::Walk-in", 1, D944,
        "service/SecurityPolicyGovernanceServiceImpl.java::Security baseline", 1, PRE_EXISTING,
        "service/impl/PatientPortalServiceImpl.java::Patient cancelled: ", 1, PRE_EXISTING,
        "service/impl/PatientPortalServiceImpl.java::Patient rescheduled: ", 1, PRE_EXISTING,
        "service/impl/PrescriptionSmsDispatchServiceImpl.java::Superseded: re-dispatched by SMS to ", 1, PRE_EXISTING,
        "service/impl/PrescriptionSmsDispatchServiceImpl.java::Not delivered: the SMS to the pharmacy failed", 1, PRE_EXISTING,
        "service/impl/SuperAdminOrganizationOverviewServiceImpl.java::Policy is inactive and requires approval", 1, PRE_EXISTING,
        "service/impl/SuperAdminOrganizationOverviewServiceImpl.java::Compliance evidence expires on ", 1, PRE_EXISTING,
        "service/pharmacy/DispenseServiceImpl.java::Dispense for prescription ", 1, PRE_EXISTING,
        "service/pharmacy/DispenseServiceImpl.java::Dispense cancelled — stock returned for prescription ", 1, PRE_EXISTING,
        "service/pharmacy/PreparedFillVoider.java::Prepared fill cancelled — stock returned for prescription ", 1, PRE_EXISTING,
        "service/pharmacy/StockOutRoutingServiceImpl.java::Prescription printed for patient to fill at external pharmacy", 1, D1000,
        "service/pharmacy/StockOutRoutingServiceImpl.java::Medication out of stock; placed on back order", 1, D1000,
        "controller/Hl7InboundController.java::Imported via HL7v2 ORU^R01 inbound adapter.", 1, D952 + " class (HL7 inbound notes)",
        "cdshooks/bpa/NewsScoreProtocolRule.java::At least one parameter scores 3 — urgent review per policy.", 1, CDS_CARD,
        "cdshooks/bpa/NewsScoreProtocolRule.java::NEWS2: {0} ({1} risk) — vitals recorded {2}", 1, CDS_CARD,
        "cdshooks/bpa/NewsScoreProtocolRule.java::⚠ Score is INCOMPLETE — not recorded: {0}. The true score can only be equal or higher.", 1, CDS_CARD,
        "cdshooks/bpa/ObHemorrhageProtocolRule.java::Most recent triggering vitals at {0}: HR={1} bpm, sBP={2} mmHg.", 1, CDS_CARD,
        "fhir/mapper/DiagnosticReportFhirMapper.java::Gram stain: ", 1, FHIR_DISPLAY,
        "fhir/mapper/DiagnosticReportFhirMapper.java::Imaging report", 1, FHIR_DISPLAY,
        "fhir/mapper/DiagnosticReportFhirMapper.java::Microbiology culture", 1, FHIR_DISPLAY,
        "fhir/mapper/ObservationFhirMapper.java::Laboratory result", 1, FHIR_DISPLAY,
        "seed/OrganizationSecuritySeeder.java::Audit all sensitive medical operations", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Controls access to administrative endpoints", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Controls access to patient data based on role", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Default access control rules for healthcare organizations", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Default audit logging requirements", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Default data protection and privacy rules", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Default organization for hospitals without specific organization assignment", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Default password strength requirements", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Default role assignment and management rules", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Default session management and timeout rules", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Minimum password length requirement", 1, SEEDED_DESCRIPTION,
        "seed/OrganizationSecuritySeeder.java::Session timeout in minutes", 1, SEEDED_DESCRIPTION);

    /** {@code file::literal} → how many places write it: every English literal reaching a text sink, every section header. */
    static Map<String, Integer> sites(String file, String rawSource) {
        String source = SourceScan.stripComments(rawSource);
        int[] starts = SourceScan.statementStarts(source);
        Map<String, Integer> out = new TreeMap<>();
        Matcher sink = TEXT_SINK.matcher(source);
        while (sink.find()) {
            int open = sink.end() - 1;
            int close = SourceScan.closingParen(source, open);
            String argument = source.substring(open + 1, close);
            // The receiver chain of the sink — from the statement's real start (a `;`
            // inside a literal does not end a statement) to the sink itself — decides
            // the exemption; the argument does not.
            String receiver = source.substring(SourceScan.statementStartBefore(starts, sink.start()), sink.start());
            if (EXEMPT_RECEIVER.matcher(receiver).find()) {
                continue;
            }
            Matcher literal = LITERAL.matcher(argument);
            while (literal.find()) {
                if (isProse(literal.group(1))) {
                    out.merge(file + "::" + literal.group(1), 1, Integer::sum);
                }
            }
            Matcher fallback = FALLBACK_WORD.matcher(argument.strip());
            if (fallback.find()) {
                out.merge(file + "::" + fallback.group(1), 1, Integer::sum);
            }
        }
        // A header already counted through a sink (`.append("[Nursing Assessment]")`) is
        // one site, not two; a header handed to a helper method is counted here.
        Matcher header = SECTION_HEADER.matcher(source);
        while (header.find()) {
            String literal = header.group();
            out.putIfAbsent(file + "::" + literal.substring(1, literal.length() - 1), 1);
        }
        return out;
    }

    @Test
    @DisplayName("no service or mapper stores or returns an English sentence beyond the listed sites")
    void storedTextIsKeyed() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                if (SCANNED_DIR.matcher(rel).matches()) {
                    actual.putAll(sites(rel, Files.readString(p, StandardCharsets.UTF_8)));
                }
            }
        }
        List<String> fresh = new ArrayList<>();
        actual.forEach((site, n) -> {
            int allowed = ALLOWED.containsKey(site) ? ALLOWED.get(site).count() : 0;
            if (n > allowed) {
                fresh.add(site + " ×" + n + " (allowed " + allowed + ")");
            }
        });
        assertThat(fresh)
            .as("stored text is read in English for good: resolve a key (MessageUtil) or store a code the portal labels"
                + SourceScan.MANDATE)
            .isEmpty();

        List<String> stale = new ArrayList<>();
        ALLOWED.forEach((site, allowance) -> {
            int n = actual.getOrDefault(site, 0);
            if (n < allowance.count()) {
                stale.add(site + ": " + n + " left, " + allowance.count() + " allowed — lower it");
            }
        });
        assertThat(stale)
            .as("a site was keyed: lower its count so the gain cannot be spent again" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D952, D989, D944-class and D1000 shapes, per site, and exempts log, audit and keys")
    void theScanCatchesTheOldShapes() {
        String source = """
            .notes(req.getPainScale() != null ? "Pain scale: " + req.getPainScale() : null)
            appendSectionIfPresent(noteBuilder, "[Nursing Assessment]", request.getNursingAssessmentNotes());
            noteBuilder.append("[Medication Reconciliation] ").append(medicationCount).append(" medication(s) reported:\\n");
            : existingNotes + "\\n\\n--- Nursing Intake ---\\n" + noteBuilder.toString().trim();
            .reason("Medication out of stock; placed on back order")
            .reason("Medication out of stock; placed on back order")
            .appointmentReason(notBlank(walkIn.getChiefComplaint()) ? walkIn.getChiefComplaint() : "Walk-in")
            .reason(MessageUtil.resolve(locale, "pharmacy.routing.backOrder"))
            .eventDescription("Correction: result " + correctionId + " supersedes released result " + supersededId)
            log.warn("Correction audit not written for result {}: {}", supersededId, ex.getMessage());
            builder.append(", ").append(name);
            notificationRepository.save(Notification.builder().title("Lab result ready").build());
            json.append('"').append(name).append('"');
            audit.record(AuditEvent.builder().title("a; b").description("Patient merged into master record"));
            notification.setMessage(eventDescription + " is ready for review");
            notifier.toUser(user).about(eventDescription).message("Your result is ready for review");
            // noteBuilder.append("Not a real section header") — a comment is not a site
            """;
        assertThat(sites("x", source)).containsOnly(
            Map.entry("x:: is ready for review", 1),
            Map.entry("x:: medication(s) reported:\\n", 1),
            Map.entry("x::Lab result ready", 1),
            Map.entry("x::Medication out of stock; placed on back order", 2),
            Map.entry("x::Pain scale: ", 1),
            Map.entry("x::Walk-in", 1),
            Map.entry("x::Your result is ready for review", 1),
            Map.entry("x::[Medication Reconciliation] ", 1),
            Map.entry("x::[Nursing Assessment]", 1),
            Map.entry("x::\\n\\n--- Nursing Intake ---\\n", 1));
    }
}
