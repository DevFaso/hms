package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provisional platform rule (unsigned-note workflow): a visit closes without waiting for the doctor, and its notes
 * still waiting for a signature go to their authors' in-basket. A close path that forgets the hook leaves an
 * unsigned note nobody is told about, the defect the user found on dev ("Terminé" over an unsigned SOAP note).
 *
 * <p>Every method of the main sources that writes an encounter's status to a closed value, or to a value it does
 * not name, calls {@code visitClosed(...)} in its own body. A write that provably never closes (a new visit's
 * initial status, the examination's end) is listed in {@link #NEVER_CLOSES} with the reason, and the list holds
 * no stale entry.
 */
class UnsignedNoteHookGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final String CLOSED = "(?:com\\.example\\.hms\\.enums\\.)?EncounterStatus\\.(?:COMPLETED|CANCELLED|LEFT_WITHOUT_BEING_SEEN)\\b";

    /** A literal closed status written on any receiver, or put in a builder. */
    private static final Pattern CLOSED_LITERAL = Pattern.compile(
        "\\.(?:setStatus|status)\\(\\s*+" + CLOSED);

    /** A status written from a value the code does not name: closed or not, the scan cannot tell. */
    private static final Pattern VARIABLE_STATUS = Pattern.compile(
        "\\b(\\w++)\\.setStatus\\(\\s*+(?!(?:com\\.example\\.hms\\.enums\\.)?EncounterStatus\\.)(?![\"A-Z])");

    private static final Pattern HOOK = Pattern.compile("\\bvisitClosed\\(");

    private static final Pattern METHOD = Pattern.compile(
        "^\\s++(?:(?:public|private|protected|static|final|synchronized)\\s++)*+[\\w.<>\\[\\],?\\s]+?\\s(\\w++)\\s*+\\(",
        Pattern.MULTILINE);

    /** File#method: why the status it writes is never a closed one. */
    private static final Map<String, String> NEVER_CLOSES = Map.of(
        "EncounterServiceImpl.java#createEncounter",
            "a new visit's status: INITIAL_STATUSES only, else defaultInitialStatus (QA D761)",
        "EncounterServiceImpl.java#applyEncounterDefaults",
            "fills a missing status with the visit's own, else ARRIVED",
        "EncounterServiceImpl.java#completeExamination",
            "next is AWAITING_RESULTS or READY_FOR_DISCHARGE",
        "PrescriptionServiceImpl.java#createEncounterSnapshot",
            "builds a prescription's anchor encounter, which never holds a clinical note",
        "DevSyntheticDataSeeder.java#createEncounter",
            "dev seed data, written before any note exists");

    @Test
    void everyMethodThatClosesAVisitCallsTheUnsignedNoteHook() throws IOException {
        List<String> missing = new ArrayList<>();
        Set<String> seenAllowed = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                String name = file.getFileName().toString();
                for (int site : closeSites(code)) {
                    String[] method = enclosingMethod(code, site);
                    String key = name + "#" + method[0];
                    if (NEVER_CLOSES.containsKey(key)) {
                        seenAllowed.add(key);
                    } else if (!HOOK.matcher(method[1]).find()) {
                        missing.add(key + " (line " + SourceScan.lineOf(code, site) + ")");
                    }
                }
            }
        }
        assertThat(missing)
            .as("a method closes a visit without UnsignedNoteFollowUp.visitClosed: its unsigned notes reach nobody"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(seenAllowed)
            .as("a NEVER_CLOSES entry no longer matches a status write: delete it")
            .containsExactlyInAnyOrderElementsOf(NEVER_CLOSES.keySet());
    }

    /** The guard sees the paths it is about: the checkout, the close-unseen, the discharge, the death. */
    @Test
    void theGuardReadsTheKnownClosePaths() throws IOException {
        assertThat(sitesIn("service/EncounterServiceImpl.java")).contains("checkOut", "closeUnseen");
        assertThat(sitesIn("service/EncounterAutoCompletionService.java"))
            .contains("completeAdmissionEncounters", "cancelAdmissionEncounters", "completeVisitsThatLedToAdmission");
        assertThat(sitesIn("service/impl/MortalityServiceImpl.java")).contains("closeEncounters");
        assertThat(sitesIn("service/ReceptionServiceImpl.java")).contains("updateEncounterStatus");
    }

    private static Set<String> sitesIn(String relative) throws IOException {
        String code = SourceScan.stripComments(Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8));
        Set<String> methods = new TreeSet<>();
        for (int site : closeSites(code)) {
            methods.add(enclosingMethod(code, site)[0]);
        }
        return methods;
    }

    private static List<Integer> closeSites(String code) {
        List<Integer> sites = new ArrayList<>();
        Matcher literal = CLOSED_LITERAL.matcher(code);
        while (literal.find()) {
            sites.add(literal.start());
        }
        Matcher variable = VARIABLE_STATUS.matcher(code);
        while (variable.find()) {
            if (declaredAsEncounter(enclosingMethod(code, variable.start())[2], variable.group(1))) {
                sites.add(variable.start());
            }
        }
        return sites;
    }

    /** The receiver is an Encounter in this method: a local, a parameter or a loop variable. */
    private static boolean declaredAsEncounter(String code, String receiver) {
        // Our entity, never the FHIR resource of the same simple name (org.hl7.fhir.r4.model.Encounter).
        return Pattern.compile("(?:(?<![\\w.])|com\\.example\\.hms\\.model\\.)Encounter\\s++"
            + Pattern.quote(receiver) + "\\b").matcher(code).find();
    }

    /**
     * The name, the body and the whole declaration (parameters included) of the method declared last before
     * {@code index} whose body holds it.
     */
    private static String[] enclosingMethod(String code, int index) {
        Matcher m = METHOD.matcher(code);
        String name = "?";
        String body = "";
        String declaration = "";
        while (m.find() && m.start() < index) {
            int open = code.indexOf('(', m.end() - 1);
            int close = SourceScan.closingParen(code, open);
            int brace = SourceScan.indexOfOutsideLiterals(code, '{', close);
            int semicolon = SourceScan.indexOfOutsideLiterals(code, ';', close);
            if (brace < 0 || (semicolon >= 0 && semicolon < brace) || brace > index) {
                continue;
            }
            int end = SourceScan.closingBrace(code, brace);
            if (end > index) {
                name = m.group(1);
                body = code.substring(brace, end + 1);
                declaration = code.substring(m.start(), end + 1);
            }
        }
        return new String[] {name, body, declaration};
    }
}
