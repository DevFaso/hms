package com.example.hms.architecture;

import com.example.hms.repository.PatientInsuranceRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of #1131 (the QA D1590 class): insurance rows are hospital-scoped
 * through their assignment. A write path that found a policy by payer and
 * number across every hospital, then tagged it with the caller's hospital,
 * moved hospital A's coverage to hospital B (A's desk lost it; B could make it
 * its primary). Both the pre-check-in and the desk's natural-key link did.
 *
 * <ul>
 *   <li>Every repository finder keyed on a policy number is scoped: to a
 *       hospital ({@code Assignment_Hospital_Id}) or to rows tagged to no
 *       hospital yet ({@code AssignmentIsNull}). An {@code exists} check
 *       writes nothing and is not a finder.</li>
 *   <li>The insurance write services tag a row only through their scoped
 *       helper, never with a bare {@code setAssignment(}.</li>
 * </ul>
 */
class PatientInsuranceScopedLookupGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");

    @Test
    void everyPolicyFinderIsHospitalScoped() {
        List<String> unscoped = new ArrayList<>();
        for (Method method : PatientInsuranceRepository.class.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.startsWith("find") || !name.contains("PolicyNumber")) {
                continue;
            }
            if (!name.contains("Assignment_Hospital_Id") && !name.contains("AssignmentIsNull")) {
                unscoped.add(name);
            }
        }
        assertThat(unscoped)
            .as("a policy finder that reaches another hospital's insurance row (review of #1131)")
            .isEmpty();
    }

    /** {file, the only method allowed to tag a row with an assignment}. */
    private static final List<String[]> TAGGERS = List.of(
        new String[] {"PatientInsuranceServiceImpl.java", "tagForStaffHospital"},
        new String[] {"impl/PatientPortalServiceImpl.java", "recordPreCheckInInsurance"});

    @Test
    void insuranceRowsAreTaggedOnlyThroughTheScopedHelper() throws IOException {
        List<String> offenders = new ArrayList<>();
        Pattern setAssignment = Pattern.compile("\\b(\\w+)\\.setAssignment\\(([^)]*)\\)");
        for (String[] tagger : TAGGERS) {
            String source = Files.readString(SERVICES.resolve(tagger[0]), StandardCharsets.UTF_8);
            Matcher m = setAssignment.matcher(source);
            while (m.find()) {
                if (!m.group(1).toLowerCase(java.util.Locale.ROOT).contains("insurance")) {
                    continue; // another entity's assignment (an appointment's), not a coverage row
                }
                if ("null".equals(m.group(2).trim())) {
                    continue; // clearing a tag never moves a row to another hospital
                }
                String method = enclosingMethod(source, m.start());
                if (!tagger[1].equals(method)) {
                    offenders.add(tagger[0] + " " + method + ": " + m.group());
                }
            }
        }
        assertThat(offenders)
            .as("an insurance row tagged outside the scoped helper (review of #1131)")
            .isEmpty();
    }

    /**
     * Review of #1131 and QA D1651: every insurance method that loads a row by
     * id, read or write, is hospital-scoped: through
     * {@code getScopedInsuranceOrThrow} (read, edit, delete) or through
     * {@code tagForStaffHospital} (the links). A method that loads with the
     * bare {@code getInsuranceOrThrow} and neither reaches another hospital's
     * row (D1651: the GET by id did, and returned A's policy to B).
     */
    @Test
    void everyInsuranceReadOrWriteByIdIsHospitalScoped() throws IOException {
        String source = Files.readString(SERVICES.resolve("PatientInsuranceServiceImpl.java"), StandardCharsets.UTF_8);
        Matcher decl = Pattern.compile("(?m)^    (?:public|private|protected)[^(\\n=]*?\\b(\\w+)\\(").matcher(source);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (decl.find()) {
            starts.add(new int[] {decl.start()});
            names.add(decl.group(1));
        }
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i)[0];
            int to = i + 1 < starts.size() ? starts.get(i + 1)[0] : source.length();
            String body = source.substring(from, to);
            String name = names.get(i);
            if ("getInsuranceOrThrow".equals(name) || "getScopedInsuranceOrThrow".equals(name)) {
                continue; // the bare loader and the scoped one itself
            }
            if (body.contains(" getInsuranceOrThrow(") && !body.contains("tagForStaffHospital(")) {
                offenders.add(names.get(i));
            }
        }
        assertThat(offenders)
            .as("an insurance read or write by id that can reach another hospital's row (#1131, D1651)")
            .isEmpty();
        assertThat(source).contains("getScopedInsuranceOrThrow(insuranceId)");
    }

    /**
     * QA D1651 class, codebase-wide: every place that reads a patient's
     * coverage by id or as a list (the insurance repository's
     * {@code findById} / {@code findByPatient_Id}, or the patient's
     * {@code getPatientInsurances()} collection) filters it to the reader's
     * hospital with {@link com.example.hms.model.PatientInsurance#isVisibleAt},
     * in the same method. The hospital-keyed finders are scoped by name and
     * are not flagged. The dev seeder only appends the row it just saved.
     */
    @Test
    void everyInsuranceReadByIdOrListIsHospitalScoped() throws IOException {
        Path main = Path.of("src/main/java");
        Pattern repoField = Pattern.compile("PatientInsuranceRepository\\s+(\\w+)\\s*;");
        List<String> offenders = new ArrayList<>();
        List<Path> files;
        try (var walk = Files.walk(main)) {
            files = walk.filter(f -> f.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            if (file.endsWith("DevSyntheticDataSeeder.java")) {
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            List<Pattern> reads = new ArrayList<>();
            reads.add(Pattern.compile("\\.getPatientInsurances\\(\\)"));
            Matcher field = repoField.matcher(source);
            while (field.find()) {
                String f = Pattern.quote(field.group(1));
                reads.add(Pattern.compile("\\b" + f + "\\s*\\.\\s*(?:findById|findByPatient_Id|findAll)\\("));
            }
            for (Pattern read : reads) {
                Matcher m = read.matcher(source);
                while (m.find()) {
                    String method = enclosingMethod(source, m.start());
                    if ("getInsuranceOrThrow".equals(method)) {
                        continue; // its callers are held by the test above
                    }
                    String body = methodBody(source, m.start());
                    if (!body.contains("isVisibleAt(")) {
                        offenders.add(main.relativize(file) + " " + method + ": " + m.group());
                    }
                }
            }
        }
        assertThat(offenders)
            .as("a read of patient insurance that is not filtered to the reader's hospital (QA D1651)")
            .isEmpty();
    }

    /** The source of the method declared last before {@code index}, up to the next declaration. */
    private static String methodBody(String source, int index) {
        Pattern decl = Pattern.compile("(?m)^    (?:public|private|protected)[^(\\n=]*?\\b(\\w+)\\(");
        Matcher before = decl.matcher(source.substring(0, index));
        int from = 0;
        while (before.find()) {
            from = before.start();
        }
        Matcher after = decl.matcher(source);
        int to = source.length();
        if (after.find(index)) {
            to = after.start();
        }
        return source.substring(from, to);
    }

    /** The name of the method declared last before {@code index}. */
    private static String enclosingMethod(String source, int index) {
        Matcher m = Pattern.compile("(?m)^    (?:public|private|protected)[^(\\n=]*?\\b(\\w+)\\(")
            .matcher(source.substring(0, index));
        String last = "?";
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }
}
