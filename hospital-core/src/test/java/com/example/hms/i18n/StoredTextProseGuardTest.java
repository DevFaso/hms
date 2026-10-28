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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * <p>The scan reads the services and mappers for a text-bearing setter or
 * builder step ({@code notes(…)}, {@code setReason(…)}, {@code .title(…)},
 * {@code .append(…)}, …) whose argument contains an English prose literal, and
 * for a section-header literal ({@code "[Nursing Assessment]"},
 * {@code "--- Nursing Intake ---"}) anywhere. Audit descriptions and log lines
 * are exempt by house decision (the audit log is English). HTML e-mail
 * markup is exempt (its text goes through the mail templates' own keys).
 *
 * <p>Each entry below is {@code file::literal}; a literal not listed fails the
 * build — resolve a key in the request locale with {@code MessageUtil}, or
 * store a code the portal labels. Delete a line when its site is converted.
 */
@DisplayName("Stored and returned text is composed from keys, not English literals")
class StoredTextProseGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern SCANNED_DIR = Pattern.compile("^(service|mapper)/.*\\.java$");

    /** Text-bearing setters and builder steps. */
    static final Pattern TEXT_SINK = Pattern.compile(
        "\\.(notes|setNotes|clinicalNotes|setClinicalNotes|reason|setReason|description|setDescription|title|setTitle"
            + "|chiefComplaint|setChiefComplaint|appointmentReason|setAppointmentReason|summary|setSummary|message"
            + "|setMessage|body|setBody|comment|setComment|comments|setComments|instructions|setInstructions|append"
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
     * A statement that is a log line or an audit row: exempt (the audit log is
     * English by house decision). A notification's title or message is user-facing
     * stored text and is NOT exempt.
     */
    private static final Pattern EXEMPT_STATEMENT = Pattern.compile(
        "\\b(?:log|LOG|logger)\\.|AuditEvent|eventDescription|auditDescription");

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

    private static final String D952 = "QA D952: nursing intake written into encounter notes with English headers";
    private static final String D989 = "QA D989: triage pain score stored as English \"Pain scale: 8\"";
    private static final String D1000 = "QA D1000: English routing reasons in the FR/ES pharmacy UI";
    private static final String D958 = "QA D958: timeline/in-basket title falls back to the English \"Lab result\"";
    private static final String PRE_EXISTING = "pre-existing English stored text (class of QA D952/D989); key it with the next touch";

    /** Sites as they stand. Shrink only. */
    private static final Map<String, String> ALLOWED = new TreeMap<>(Map.ofEntries(
        Map.entry("service/ClinicalDashboardServiceImpl.java::Appointments Today", PRE_EXISTING),
        Map.entry("service/ClinicalDashboardServiceImpl.java::Pending Results", PRE_EXISTING),
        Map.entry("service/ClinicalDashboardServiceImpl.java::Active Patients", PRE_EXISTING),
        Map.entry("service/ClinicalDashboardServiceImpl.java::Refill Requests", PRE_EXISTING),
        Map.entry("service/DepartmentServiceImpl.java::Auto-provisioned hospital admin assignment for department creation", PRE_EXISTING),
        Map.entry("service/EncounterServiceImpl.java::Pain scale: ", D989),
        Map.entry("service/EncounterServiceImpl.java::[Nursing Assessment]", D952),
        Map.entry("service/EncounterServiceImpl.java::[Pain Assessment]", D952),
        Map.entry("service/EncounterServiceImpl.java::[Fall Risk]", D952),
        Map.entry("service/EncounterServiceImpl.java::[Medication Reconciliation] ", D952),
        Map.entry("service/EncounterServiceImpl.java:: medication(s) reported:\\n", D952),
        Map.entry("service/EncounterServiceImpl.java::\\n\\n--- Nursing Intake ---\\n", D952),
        Map.entry("service/EncounterServiceImpl.java::Unknown", D952 + " (medication name fallback)"),
        Map.entry("service/LabResultServiceImpl.java::Lab result", D958),
        Map.entry("service/ReceptionServiceImpl.java::Walk-in", "QA D944: the walk-in's reason falls back to the English \"Walk-in\" in the queue"),
        Map.entry("service/SecurityPolicyGovernanceServiceImpl.java::Security baseline", PRE_EXISTING),
        Map.entry("service/impl/PatientPortalServiceImpl.java::Patient cancelled: ", PRE_EXISTING),
        Map.entry("service/impl/PatientPortalServiceImpl.java::Patient rescheduled: ", PRE_EXISTING),
        Map.entry("service/impl/PrescriptionSmsDispatchServiceImpl.java::Superseded: re-dispatched by SMS to ", PRE_EXISTING),
        Map.entry("service/impl/PrescriptionSmsDispatchServiceImpl.java::Not delivered: the SMS to the pharmacy failed", PRE_EXISTING),
        Map.entry("service/impl/SuperAdminOrganizationOverviewServiceImpl.java::Policy is inactive and requires approval", PRE_EXISTING),
        Map.entry("service/impl/SuperAdminOrganizationOverviewServiceImpl.java::Compliance evidence expires on ", PRE_EXISTING),
        Map.entry("service/pharmacy/DispenseServiceImpl.java::Dispense for prescription ", PRE_EXISTING),
        Map.entry("service/pharmacy/DispenseServiceImpl.java::Dispense cancelled — stock returned for prescription ", PRE_EXISTING),
        Map.entry("service/pharmacy/PreparedFillVoider.java::Prepared fill cancelled — stock returned for prescription ", PRE_EXISTING),
        Map.entry("service/pharmacy/StockOutRoutingServiceImpl.java::Prescription printed for patient to fill at external pharmacy", D1000),
        Map.entry("service/pharmacy/StockOutRoutingServiceImpl.java::Medication out of stock; placed on back order", D1000)));

    /** {@code file::literal} for every English literal that reaches a text sink or is a section header (comments stripped). */
    static Set<String> sites(String file, String rawSource) {
        String source = SourceScan.stripComments(rawSource);
        Set<String> out = new TreeSet<>();
        Matcher sink = TEXT_SINK.matcher(source);
        while (sink.find()) {
            int open = sink.end() - 1;
            int close = closingParen(source, open);
            String argument = source.substring(open + 1, close);
            // The receiver chain of the sink — from the statement's start (a `;`
            // inside a literal does not end a statement) to the sink itself — decides
            // the exemption; the argument does not, so a notification whose message
            // happens to mention `eventDescription` is still scanned.
            String receiver = source.substring(SourceScan.statementStart(source, sink.start()), sink.start());
            if (EXEMPT_STATEMENT.matcher(receiver).find()) {
                continue;
            }
            Matcher literal = LITERAL.matcher(argument);
            while (literal.find()) {
                if (isProse(literal.group(1))) {
                    out.add(file + "::" + literal.group(1));
                }
            }
            Matcher fallback = FALLBACK_WORD.matcher(argument.strip());
            if (fallback.find()) {
                out.add(file + "::" + fallback.group(1));
            }
        }
        Matcher header = SECTION_HEADER.matcher(source);
        while (header.find()) {
            String literal = header.group();
            out.add(file + "::" + literal.substring(1, literal.length() - 1));
        }
        return out;
    }

    private static int closingParen(String source, int open) {
        return SourceScan.closingParen(source, open);
    }

    @Test
    @DisplayName("no service or mapper stores or returns an English sentence beyond the listed sites")
    void storedTextIsKeyed() throws IOException {
        Set<String> actual = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                if (SCANNED_DIR.matcher(rel).matches()) {
                    actual.addAll(sites(rel, Files.readString(p, StandardCharsets.UTF_8)));
                }
            }
        }
        List<String> fresh = new ArrayList<>();
        for (String site : actual) {
            if (!ALLOWED.containsKey(site)) {
                fresh.add(site);
            }
        }
        assertThat(fresh)
            .as("stored text is read in English for good: resolve a key (MessageUtil) or store a code the portal labels"
                + SourceScan.MANDATE)
            .isEmpty();

        List<String> stale = new ArrayList<>();
        for (String site : ALLOWED.keySet()) {
            if (!actual.contains(site)) {
                stale.add(site);
            }
        }
        assertThat(stale)
            .as("the site was keyed: delete its line so the gain cannot be spent again" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D952, D989, D944-class and D1000 shapes and exempts audit, log and keys")
    void theScanCatchesTheOldShapes() {
        String source = """
            .notes(req.getPainScale() != null ? "Pain scale: " + req.getPainScale() : null)
            appendSectionIfPresent(noteBuilder, "[Nursing Assessment]", request.getNursingAssessmentNotes());
            noteBuilder.append("[Medication Reconciliation] ").append(medicationCount).append(" medication(s) reported:\\n");
            : existingNotes + "\\n\\n--- Nursing Intake ---\\n" + noteBuilder.toString().trim();
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
            """;
        assertThat(sites("x", source)).containsExactly(
            "x:: is ready for review",
            "x:: medication(s) reported:\\n",
            "x::Lab result ready",
            "x::Medication out of stock; placed on back order",
            "x::Pain scale: ",
            "x::Walk-in",
            "x::[Medication Reconciliation] ",
            "x::[Nursing Assessment]",
            "x::\\n\\n--- Nursing Intake ---\\n");
    }
}
