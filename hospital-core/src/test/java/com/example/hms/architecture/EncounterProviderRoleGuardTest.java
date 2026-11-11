package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1164 (round 21): the walk-in refused the midwife the appointment
 * check-in accepted ("Informations du personnel invalides pour la
 * rencontre"), because the visit's provider check listed roles by hand
 * (doctor, nurse, hospital admin) and left out the midwife, the physician and
 * the surgeon. A visit takes its provider through one list,
 * {@link RoleValidator#ENCOUNTER_PROVIDER_ROLE_CODES}.
 *
 * <ul>
 *   <li>the list holds every licensed physician role, the nurse and the midwife;</li>
 *   <li>no method of {@code src/main} that refuses a visit's staff
 *       ({@code encounter.staff.invalid}) decides with a hand-written role
 *       predicate ({@code isDoctor}, {@code isNurse}, {@code isMidwife}, …).</li>
 * </ul>
 */
class EncounterProviderRoleGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    /** A line that declares something called like a method: words, then a name, then "(". */
    private static final Pattern METHOD = Pattern.compile(
        "(?m)^[ \\t]*+(?:[\\w<>\\[\\],.?]++[ \\t]++)++(\\w+)[ \\t]*+\\(");
    private static final Pattern HAND_LIST = Pattern.compile(
        "roleValidator\\.is(Doctor|Nurse|Midwife|Physician|Surgeon|HospitalAdmin)\\(");

    @Test
    void theProviderListHoldsEveryClinicalRoleThatRunsAVisit() {
        assertThat(RoleValidator.ENCOUNTER_PROVIDER_ROLE_CODES)
            .contains("MIDWIFE", "NURSE")
            .containsAll(RoleValidator.LICENSED_PHYSICIAN_ROLE_CODES);
    }

    @Test
    void noVisitStaffCheckListsRolesByHand() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checks = 0;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("encounter.staff.invalid")) {
                    continue;
                }
                Matcher m = METHOD.matcher(source);
                while (m.find()) {
                    int close = SourceScan.closingParen(source, m.end() - 1);
                    int open = source.indexOf('{', close);
                    int semicolon = source.indexOf(';', close);
                    if (open < 0 || (semicolon >= 0 && semicolon < open)) {
                        continue;
                    }
                    String body = source.substring(open, SourceScan.closingBrace(source, open) + 1);
                    if (!body.contains("MSG_ENCOUNTER_STAFF_INVALID") && !body.contains("encounter.staff.invalid")) {
                        continue;
                    }
                    Matcher hand = HAND_LIST.matcher(body);
                    if (body.contains("isEncounterProvider(")) {
                        checks++;
                    }
                    if (hand.find()) {
                        offenders.add(MAIN.relativize(file) + "#" + m.group(1) + " uses is" + hand.group(1));
                    }
                }
            }
        }
        assertThat(checks).as("the visit's staff checks found").isGreaterThanOrEqualTo(3);
        assertThat(offenders)
            .as("a visit's staff check that lists roles by hand leaves one out (QA D1164): use"
                + " roleValidator.isEncounterProvider%s", SourceScan.MANDATE)
            .isEmpty();
    }
}
