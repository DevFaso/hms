package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of #1246 (findings 2 and round 2): an administrator's form that finds
 * an EXISTING account by something typed (a username, an e-mail, a phone, a
 * licence number) and then attaches a role to it is a way to reach any account
 * of the platform. An organisation admin named a patient by phone number on
 * {@code POST /assignments}, and another organisation's clinician by licence
 * number on {@code POST /users/admin-register}, which also echoed their
 * contact details, forced their password and restored a deleted account.
 *
 * <p>Both lookups are now held to one rule
 * ({@code UserAccountAccess.isStaffOfCallersOrganisation}). This keeps it so:
 * the platform-wide licence lookup has ONE caller, and each of the two
 * lookups is followed, in its own method, by the check that holds it.
 */
class ExistingAccountLookupGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** {@code file -> number of calls} of a method across the main sources, the repository's own declaration aside. */
    private static Map<String, Integer> callers(String call, String declaredIn) throws IOException {
        Pattern pattern = Pattern.compile(Pattern.quote(call) + "\\s*\\(");
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(sources).as("the main sources were read").hasSizeGreaterThan(1000);
            for (Path source : sources) {
                String name = MAIN.relativize(source).toString().replace('\\', '/');
                if (name.equals(declaredIn)) {
                    continue;
                }
                Matcher matcher = pattern.matcher(Files.readString(source, StandardCharsets.UTF_8));
                int calls = 0;
                while (matcher.find()) {
                    calls++;
                }
                if (calls > 0) {
                    found.put(name, calls);
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("the platform-wide licence lookup has one caller, and that caller holds the match before it uses it")
    void theLicenceLookupIsHeldWhereItIsMade() throws IOException {
        assertThat(callers("findUserIdByLicense", "repository/StaffRepository.java"))
            .as("StaffRepository.findUserIdByLicense finds a clinician of ANY hospital: a new caller must hold the"
                + " match to UserAccountAccess (who may attach it, what is echoed), as admin-register does")
            .containsExactly(Map.entry("service/UserServiceImpl.java", 1));

        String service = read("service/UserServiceImpl.java");
        int lookup = service.indexOf("staffRepository.findUserIdByLicense(");
        int held = service.indexOf("requireMayAttachByLicence(existingByLicense.get()", lookup);
        int used = service.indexOf("existingByIdentity.or(() -> existingByLicense)", lookup);
        assertThat(held).as("the match is held to requireMayAttachByLicence").isGreaterThan(lookup);
        assertThat(used).as("...before the account it found is used").isGreaterThan(held);
        // What the rule is made of: the organisation's own staff, and no restore of a deleted account.
        String rule = service.substring(service.indexOf("private void requireMayAttachByLicence("));
        rule = rule.substring(0, rule.indexOf("\n    }\n"));
        assertThat(rule).contains("accountAccess.reachesOnlyThroughOrganisation(")
            .contains("accountAccess.isStaffOfCallersOrganisation(")
            .contains("held.isDeleted()");
        // The answer about an account the caller does not administer is the confirmation only.
        assertThat(service).contains("attachedByLicence && !administersAttached")
            .contains("? confirmationOnly(mapped)");
    }

    @Test
    @DisplayName("the holder a new assignment names is held to the same rule, right after it is found")
    void theAssignmentHolderIsHeldWhereItIsFound() throws IOException {
        String service = read("service/UserRoleHospitalAssignmentServiceImpl.java");
        int found = service.indexOf("User user = resolveUser(dto);");
        assertThat(found).as("createAssignment resolves the holder").isPositive();
        String next = service.substring(found, found + 260);
        assertThat(next).as("...and holds it before anything is done with it")
            .contains("requireHolderWithinOrganisation(user, hospital, dto);");
        String rule = service.substring(service.indexOf("private void requireHolderWithinOrganisation("));
        rule = rule.substring(0, rule.indexOf("\n    }\n"));
        assertThat(rule).contains("accountAccess.reachesOnlyThroughOrganisation(")
            .contains("accountAccess.isStaffOfCallersOrganisation(holder)");
    }
}
