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
