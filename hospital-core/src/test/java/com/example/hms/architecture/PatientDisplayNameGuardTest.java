package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D1036 / D926 (class guard, server half): a patient's name is sent whole.
 * The appointment, the reception queue, the snapshot and the e-mails built it
 * as {@code first + " " + last}, so a middle name captured at registration was
 * never shown. {@code Patient.getFullName()} is the one assembly (each part
 * once, single-spaced).
 *
 * <p>Round 18: the first version matched only {@code p.getFirstName() + " " +
 * p.getLastName()} and {@code getFullName(p.getFirstName(), p.getLastName())},
 * so the wristband ({@code safe(…) + " " + safe(…)}), the invoice PDF, the
 * tracker ({@code nullSafe(…)}), the lab mapper ({@code nullToEmpty(…)}) and
 * every "first = …; last = …;" helper slipped past it, and D1036 failed a
 * third round. The scan now works per METHOD: a method that reads both the
 * first and the last name of the same record carrying a middle name (the
 * entity and every DTO with a {@code middleName} field, found by scanning the
 * sources) must also read its middle name or call {@code getFullName()},
 * whatever wraps or joins the parts.
 *
 * <p>The portal half is {@code hospital-portal/scripts/lib/person-name-display.test.mjs}.
 */
class PatientDisplayNameGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** A receiver chain: {@code p}, {@code enc.getPatient()}, {@code invoice.getPatient()}. */
    private static final Pattern FIRST_NAME_READ = Pattern.compile(
        "([A-Za-z_$][\\w$]*(?:\\(\\))?(?:\\.[A-Za-z_$][\\w$]*\\(\\))*)\\.getFirstName\\(\\)");

    private static final Pattern METHOD_HEADER = Pattern.compile(
        "(?m)^[ \\t]+(?:(?:public|protected|private|static|final|synchronized|default|abstract)\\s+)*"
            + "(?:<[^>;{}]+>\\s+)?([\\w.$]+(?:<[^;{}()]*>)?(?:\\[\\])*)\\s+([a-zA-Z_$][\\w$]*)\\s*\\(");

    private static final Set<String> NOT_A_TYPE = Set.of("return", "new", "throw", "else", "case", "yield");

    /**
     * Sites tolerated, each with its reason. A name MATCH is not a display:
     * matching keeps "first last" so a search without the middle name still
     * finds the patient. They shrink, never grow.
     */
    private static final Map<String, GuardAllowlist.Allowance> ALLOWED = GuardAllowlist.allow(
        "empi/probabilistic/EmpiProbabilisticMatcher.java", 2,
            "a probabilistic name MATCH (Jaro-Winkler on first and last), not a display",
        "service/ReceptionServiceImpl.java", 1,
            "PatientMatchKeys.nameContains: a name MATCH, not a display (search by first+last finds the patient)",
        "service/UserServiceImpl.java", 1,
            "isSamePerson: the registration identity MATCH on the request's names (QA D1061), not a display",
        "utility/PatientMatchKeys.java", 1,
            "PatientMatchKeys.sameIdentity: the registration identity MATCH (QA D1061 / D1064), not a display",
        "service/pharmacy/partner/SmsPartnerNotificationChannel.java", 1,
            "two INITIALS sent to a partner pharmacy by SMS (data minimisation), not a name display");

    @Test
    void aPatientsNameIsAssembledOnlyByGetFullName() throws IOException {
        Set<String> nameTypes = typesWithAMiddleName();
        assertThat(nameTypes).as("the record types that carry a middle name").contains("Patient", "PatientResponseDTO");
        List<String> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(f).toString().replace('\\', '/');
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                partsWithoutMiddle(src, nameTypes).forEach(receiver -> sites.add(rel));
            }
        }
        Map<String, Integer> actual = GuardAllowlist.counts(sites);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a patient name built from its first and last name, dropping the middle name (QA D1036): use "
                + "Patient.getFullName() (or read getMiddleName() too); " + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: lower its allowance").isEmpty();
    }

    /**
     * The JPQL half: a {@code SELECT new …(…)} projection that builds a
     * displayed name with CONCAT from {@code firstName} and {@code lastName}
     * (the multi-hospital patient summary did) also carries the middle name.
     * A CONCAT in a WHERE clause is a search MATCH and is not looked at.
     */
    @Test
    void noProjectionConcatenatesANameWithoutItsMiddleName() throws IOException {
        List<String> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                for (String projection : projectionsWithoutMiddle(src)) {
                    sites.add(MAIN.relativize(f).toString().replace('\\', '/') + ": " + projection);
                }
            }
        }
        assertThat(sites).as("a SELECT new projection that CONCATs firstName and lastName without middleName "
            + "(QA D1036); " + SourceScan.MANDATE).isEmpty();
    }

    static List<String> projectionsWithoutMiddle(String src) {
        List<String> out = new ArrayList<>();
        Matcher sel = Pattern.compile("(?i)SELECT\\s+new\\s+[\\w.]+\\s*\\(").matcher(src);
        while (sel.find()) {
            int close = src.indexOf("FROM", sel.end());
            if (close < 0) {
                continue;
            }
            String args = src.substring(sel.end(), close);
            if (args.contains("CONCAT") && args.contains("firstName") && args.contains("lastName")
                && !args.contains("middleName")) {
                out.add(args.strip().lines().findFirst().orElse(""));
            }
        }
        return out;
    }

    @Test
    void theProjectionScanSeesAConcat() {
        assertThat(projectionsWithoutMiddle("\"SELECT new a.B(p.id, CONCAT(p.firstName, ' ', p.lastName)) FROM P p\""))
            .hasSize(1);
        assertThat(projectionsWithoutMiddle(
            "\"SELECT new a.B(p.id, CONCAT(p.firstName, ' ', p.middleName, ' ', p.lastName)) FROM P p\"")).isEmpty();
    }

    /**
     * One entry per (method, receiver) whose method reads the receiver's first
     * and last name but neither its middle name nor its full name, where the
     * receiver is a record that carries a middle name.
     */
    static List<String> partsWithoutMiddle(String src, Set<String> nameTypes) {
        List<String> out = new ArrayList<>();
        List<int[]> bodies = methodBodies(src);
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = FIRST_NAME_READ.matcher(src);
        while (m.find()) {
            String receiver = m.group(1);
            int[] body = innermost(bodies, m.start());
            if (body == null || !seen.add(body[0] + "|" + receiver)) {
                continue;
            }
            String text = src.substring(body[0], body[1]);
            boolean partsOnly = text.contains(receiver + ".getLastName()")
                && !text.contains(receiver + ".getMiddleName()")
                && !text.contains(receiver + ".getFullName()");
            if (partsOnly && carriesAMiddleName(receiver, src, text, nameTypes)) {
                out.add(receiver);
            }
        }
        return out;
    }

    private static boolean carriesAMiddleName(String receiver, String src, String body, Set<String> nameTypes) {
        if (receiver.endsWith(".getPatient()") || receiver.equals("getPatient()")) {
            return true;
        }
        if (receiver.contains("(")) {
            return false; // staff.getUser(), getCurrentUser(): a user account has no middle name
        }
        String declared = declaredType(receiver, body);
        if (declared == null) {
            declared = declaredType(receiver, src);
        }
        if (declared != null) {
            return nameTypes.contains(declared.substring(declared.lastIndexOf('.') + 1));
        }
        // An untyped lambda parameter over patients: patient -> patient.getFirstName() …
        return receiver.matches("patient\\w*|pt");
    }

    /** The type {@code name} is declared with in {@code text} ({@code Patient p}, {@code (Patient p)}), or null. */
    private static String declaredType(String name, String text) {
        Matcher d = Pattern.compile("\\b([A-Z][\\w.$]*)(?:<[^;{}()]*>)?\\s+" + Pattern.quote(name) + "\\s*[=,);:]")
            .matcher(text);
        return d.find() ? d.group(1) : null;
    }

    /** [bodyStart, bodyEnd) of every method and constructor body. */
    static List<int[]> methodBodies(String src) {
        List<int[]> out = new ArrayList<>();
        Matcher h = METHOD_HEADER.matcher(src);
        while (h.find()) {
            if (NOT_A_TYPE.contains(h.group(1))) {
                continue;
            }
            int closeParen = SourceScan.closingParen(src, h.end() - 1);
            if (closeParen < 0) {
                continue;
            }
            int open = SourceScan.indexOfOutsideLiterals(src, '{', closeParen);
            int semi = SourceScan.indexOfOutsideLiterals(src, ';', closeParen);
            if (open < 0 || (semi >= 0 && semi < open)) {
                continue;
            }
            int close = SourceScan.closingBrace(src, open);
            if (close > open) {
                out.add(new int[] {open, close});
            }
        }
        return out;
    }

    private static int[] innermost(List<int[]> bodies, int at) {
        int[] best = null;
        for (int[] b : bodies) {
            if (b[0] <= at && at < b[1] && (best == null || b[1] - b[0] < best[1] - best[0])) {
                best = b;
            }
        }
        return best;
    }

    /** Every main class that declares a {@code middleName} field: the records a full name is built from. */
    static Set<String> typesWithAMiddleName() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Pattern field = Pattern.compile("\\bprivate\\s+(?:final\\s+)?String\\s+middleName\\b"
            + "|\\brecord\\s+\\w+\\s*\\([^)]*\\bString\\s+middleName\\b");
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (field.matcher(Files.readString(f, StandardCharsets.UTF_8)).find()) {
                    String name = f.getFileName().toString();
                    out.add(name.substring(0, name.length() - ".java".length()));
                }
            }
        }
        return out;
    }

    @Test
    void theScanSeesTheShapes() {
        Set<String> types = Set.of("Patient");
        // The round-18 misses: wrapped parts, a join helper, and first/last locals.
        assertThat(partsWithoutMiddle(method("Patient p",
            "return safe(p.getFirstName()) + \" \" + safe(p.getLastName());"), types)).containsExactly("p");
        assertThat(partsWithoutMiddle(method("Patient patient",
            "return (nullSafe(patient.getFirstName()) + \" \" + nullSafe(patient.getLastName())).trim();"), types))
            .containsExactly("patient");
        assertThat(partsWithoutMiddle(method("Patient patient",
            "return (nullToEmpty(patient.getFirstName()) + \" \" + nullToEmpty(patient.getLastName())).trim();"),
            types)).containsExactly("patient");
        assertThat(partsWithoutMiddle(method("Invoice invoice",
            "return safe(invoice.getPatient().getFirstName()) + \" \" + safe(invoice.getPatient().getLastName());"),
            types)).containsExactly("invoice.getPatient()");
        assertThat(partsWithoutMiddle(method("Patient pt",
            "String f = pt.getFirstName(); String l = pt.getLastName(); return f + \" \" + l;"), types))
            .containsExactly("pt");
        assertThat(partsWithoutMiddle(method("Patient p", "return join(p.getFirstName(), p.getLastName());"), types))
            .containsExactly("p");
        // Whole names, and records with no middle name, are not instances.
        assertThat(partsWithoutMiddle(method("Patient p", "return p.getFullName();"), types)).isEmpty();
        assertThat(partsWithoutMiddle(method("Patient p",
            "return join(p.getFirstName(), p.getMiddleName(), p.getLastName());"), types)).isEmpty();
        assertThat(partsWithoutMiddle(method("User u", "return u.getFirstName() + \" \" + u.getLastName();"), types))
            .isEmpty();
        assertThat(partsWithoutMiddle(method("Staff staff",
            "return staff.getUser().getFirstName() + \" \" + staff.getUser().getLastName();"), types)).isEmpty();
    }

    private static String method(String param, String body) {
        return "class X {\n    String name(" + param + ") {\n        " + body + "\n    }\n}\n";
    }
}
