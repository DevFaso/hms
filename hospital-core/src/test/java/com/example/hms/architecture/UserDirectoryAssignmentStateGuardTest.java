package com.example.hms.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

class UserDirectoryAssignmentStateGuardTest {
    @Test
    void everyDirectorySummaryUsesLiveAssignmentsAndSeparatesAccountState() throws Exception {
        Path root = Path.of("src/main/java/com/example/hms");
        Pattern historicalOnly = Pattern.compile("\\.toSummaryDTO\\(\\s*\\w+\\s*\\)");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(path));
                assertThat(historicalOnly.matcher(source).find())
                    .as("directory caller %s must supply visible assignments", path).isFalse();
            }
        }
        String mapper = Files.readString(root.resolve("mapper/UserMapper.java"));
        assertThat(mapper).contains("safeRolesFromAssignments(activeOnly(visible))",
            "dto.setAccessActive(!roles.isEmpty())", "dto.setRoleCount(roles.size())",
            "retired.size() == 1", "dto.setReactivationOptions(retired)");
        String service = Files.readString(root.resolve("service/UserServiceImpl.java"));
        assertThat(service).contains("findDirectoryAssignments(ids, scope.everyone(), directoryHospitals(scope))",
            "new DirectoryScope(false, one, scope.seesPatientContacts())", "accountAccess.assignmentScope()", "filter(recoveryScope::mayChange)", "findLiveChatRecipients(", "findPrivilegedDirectoryIds(", "findDirectoryProfiles(");
        int begin = mapper.indexOf("private UserSummaryDTO accountSummary");
        int end = mapper.indexOf("private String deriveProfileType", begin);
        assertThat(mapper.substring(begin, end)).doesNotContain("toSummaryDTO(user)", "safeRolesFromUserRoles(", "deriveProfileType(");
        String repository = Files.readString(root.resolve("repository/UserRoleHospitalAssignmentRepository.java"));
        assertThat(repository).contains("a.user.id IN :userIds", ":allHospitals = true OR h.id IN :hospitalIds");
    }

    @Test
    void staffAndUserListsDisplayAccessStateAndRecoveryNavigatesToTheExistingWorkflow() throws Exception {
        Path portal = Path.of("../hospital-portal/src/app");
        assertThat(Files.readString(portal.resolve("staff/staff-list.html")))
            .contains("staff-status", "COMMON.INACTIVE", "staff-reactivate", "reactivationHref(member)");
        assertThat(Files.readString(portal.resolve("users/user-list.html")))
            .contains("isDirectoryActive(user)", "user-reactivate-access", "ACCOUNT_RETAINED_NO_ACCESS");
        String link = Files.readString(portal.resolve("shared/assignment-reactivation.ts"));
        assertThat(link).contains("/admin-assignments?reactivate=", "encodeURIComponent(assignmentId)")
            .doesNotContain(".post(", ".put(", ".patch(");
    }
    @Test
    void recoveryUsesAssignmentRightsAndExplicitReinvitationWithoutAutomaticSend() throws Exception {
        Path portal = Path.of("../hospital-portal/src/app");
        assertThat(Files.readString(portal.resolve("users/user-list.ts")))
            .contains("canRecoverAssignments", "reactivationOptions(user: UserSummary)");
        String users = Files.readString(portal.resolve("users/user-list.html"));
        assertThat(users).contains("user-recovery-options", "option.hospitalName", "option.roleCode | roleLabel")
            .doesNotContain("isManageable(user)) {\n                    @if (reactivationHref");
        assertThat(Files.readString(portal.resolve("admin-assignments/admin-assignments.html")))
            .contains("!row.active && !isOwnRow(row)", "assignment-reinvite", "openRegen(row)");
    }

}
