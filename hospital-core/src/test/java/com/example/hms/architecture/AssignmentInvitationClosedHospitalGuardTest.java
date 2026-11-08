package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * QA D789 re-test guard. The round-1 fix put the archived-hospital rule
 * ({@code FacilityAssignmentGuard.requireOpenForAssignment}) on create,
 * activate and regenerate-code, and {@code POST /assignments/{id}/resend-notification}
 * still queued an EMAIL and an SMS activation for a row at an archived
 * hospital: the rule was on the paths QA reported, not on the class of paths.
 *
 * <p>This test reads {@code UserRoleHospitalAssignmentServiceImpl} and follows
 * its own calls from every public method to every place an invitation leaves
 * or a row is switched on: the e-mail and SMS senders, the
 * {@code AssignmentCreatedEvent} (sent AFTER_COMMIT), the public re-send's
 * code rotation, the invitation links and an activation. Every such path must
 * pass through a method that asks the rule ({@code requireOpenForAssignment},
 * {@code atClosedHospital}, {@code isClosed} or, for the database claim,
 * {@code FacilityAssignmentGuard.CLOSED_STATES}) before it reaches the sink.
 * The entry points found must also be exactly {@link #ENTRY_POINTS}, each
 * replayed against an archived hospital in {@code ArchivedHospitalAssignmentTest}:
 * a new path that sends an invitation fails here until it is guarded and listed.
 */
class AssignmentInvitationClosedHospitalGuardTest {

    private static final Path SERVICE =
        Paths.get("src/main/java/com/example/hms/service/UserRoleHospitalAssignmentServiceImpl.java");

    /** Methods that send an invitation, whoever calls them. */
    private static final Set<String> SINK_METHODS = Set.of(
        "sendAssignmentEmailNotification", "sendAssignmentSmsNotifications");

    /** Text that sends, rotates, links or activates an invitation. */
    private static final List<String> SINK_TOKENS = List.of(
        "new AssignmentCreatedEvent(", "claimActivationResend(", "assignmentLinkService.", "setActive(true)");

    private static final List<String> GUARD_TOKENS = List.of(
        "requireOpenForAssignment(", "atClosedHospital(", "isClosed(", "FacilityAssignmentGuard.CLOSED_STATES");

    /**
     * Every public method that reaches an invitation sink, and what replays it
     * against an archived hospital. Shrinks only when a path is removed.
     */
    private static final Map<String, String> ENTRY_POINTS = Map.ofEntries(
        Map.entry("assignRole", "nurseInArchivedHospital"),
        Map.entry("assignRoleOnAccountCreation", "accountCreationPath"),
        Map.entry("assignRoleToMultipleScopes", "multiScopeSkipsTheArchivedHospital"),
        Map.entry("bulkImportAssignments", "bulkImportLine"),
        Map.entry("updateAssignment", "updateThatActivatesIsRefused"),
        Map.entry("getAssignmentById", "linksAreWithheld"),
        Map.entry("getAllAssignments", "linksAreWithheld"),
        Map.entry("regenerateAssignmentCode", "regenerateIsRefused"),
        Map.entry("regenerateProviderStaffAssignmentCodes", "regenerateIsRefused"),
        Map.entry("confirmAssignment", "registrarConfirmIsRefused"),
        Map.entry("getAssignmentPublicView", "publicViewHasNoLink"),
        Map.entry("verifyAssignmentByCode", "activationIsRefused"),
        // QA D1123: "I have a code" activates through verifyAssignmentByCode.
        Map.entry("verifyAssignmentByPhone", "activationByPhoneIsRefused"),
        Map.entry("reissuePendingInvitationsForRestoredUser", "restoredAccountSkipsTheClosedInvitation"),
        Map.entry("sendNotifications", "afterCommitSendIsSkipped"),
        Map.entry("resendPendingActivationCode", "publicResendNeitherRotatesNorSends"),
        Map.entry("resendNotifications", "resendNotificationIsRefused"));

    private static final Pattern METHOD = Pattern.compile(
        "(?m)^    (?:@\\w+(?:\\([^)]*\\))?\\s+)*(public|protected|private)\\b[^\\n;={(]*?\\b(\\w+)\\s*\\(");
    private static final Pattern CALL = Pattern.compile("(?:\\b|::)(\\w+)\\s*\\(|::(\\w+)");

    @Test
    void everyInvitationPathAsksTheClosedHospitalRule() throws IOException {
        Map<String, Method> methods = parse(stripCommentsAndStrings(Files.readString(SERVICE, StandardCharsets.UTF_8)));
        assertThat(methods).as("parsed methods").containsKeys("resendNotifications", "notifyAssignee",
            "sendAssignmentEmailNotification", "activateVerifiedAssignment", "reissueCode");

        List<String> unguarded = new ArrayList<>();
        Set<String> entryPoints = new TreeSet<>();
        for (Method m : methods.values()) {
            if (m.isPublic && reachesSink(m.name, methods, new HashSet<>())) {
                entryPoints.add(m.name);
                walk(m.name, methods, new ArrayDeque<>(), new HashSet<>(), unguarded);
            }
        }

        assertThat(unguarded)
            .as("paths from a public method to an invitation sink with no closed-hospital check on the way")
            .isEmpty();
        assertThat(entryPoints)
            .as("public methods that send, link or activate an invitation: each must be replayed against "
                + "an archived hospital in ArchivedHospitalAssignmentTest and listed in ENTRY_POINTS")
            .containsExactlyInAnyOrderElementsOf(ENTRY_POINTS.keySet());
    }

    @Test
    void everyListedEntryPointHasItsReplay() throws IOException {
        String replays = Files.readString(
            Paths.get("src/test/java/com/example/hms/service/ArchivedHospitalAssignmentTest.java"),
            StandardCharsets.UTF_8);
        for (Map.Entry<String, String> e : ENTRY_POINTS.entrySet()) {
            assertThat(replays)
                .as(e.getKey() + " is replayed by ArchivedHospitalAssignmentTest." + e.getValue())
                .contains("void " + e.getValue() + "(");
        }
    }

    /**
     * Depth-first from {@code name}. A guarded method ends the path safely
     * (the rule is asked there, whatever it then calls); an unguarded method
     * that is or holds a sink records the path.
     */
    private static void walk(String name, Map<String, Method> methods, Deque<String> path, Set<String> seen,
                             List<String> unguarded) {
        Method m = methods.get(name);
        if (m == null || !seen.add(name)) {
            return;
        }
        path.addLast(name);
        if (GUARD_TOKENS.stream().noneMatch(m.body::contains)) {
            if (SINK_METHODS.contains(name) || SINK_TOKENS.stream().anyMatch(m.body::contains)) {
                unguarded.add(String.join(" -> ", path));
            }
            for (String callee : m.callees) {
                walk(callee, methods, path, seen, unguarded);
            }
        }
        path.removeLast();
    }

    private static boolean reachesSink(String name, Map<String, Method> methods, Set<String> seen) {
        Method m = methods.get(name);
        if (m == null || !seen.add(name)) {
            return false;
        }
        if (SINK_METHODS.contains(name) || SINK_TOKENS.stream().anyMatch(m.body::contains)) {
            return true;
        }
        return m.callees.stream().anyMatch(c -> reachesSink(c, methods, seen));
    }

    private record Method(String name, boolean isPublic, String body, Set<String> callees) {
    }

    /** Overloads merge into one entry: a call by name may reach either. */
    private static Map<String, Method> parse(String src) {
        Map<String, StringBuilder> bodies = new LinkedHashMap<>();
        Map<String, Boolean> publics = new LinkedHashMap<>();
        Matcher m = METHOD.matcher(src);
        while (m.find()) {
            int open = src.indexOf('{', m.end());
            int semi = src.indexOf(';', m.end());
            if (open < 0 || (semi >= 0 && semi < open)) {
                continue;
            }
            int close = matchingBrace(src, open);
            String name = m.group(2);
            bodies.computeIfAbsent(name, k -> new StringBuilder()).append(src, open, close + 1).append('\n');
            publics.merge(name, "public".equals(m.group(1)), Boolean::logicalOr);
        }
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> e : bodies.entrySet()) {
            String body = e.getValue().toString();
            Set<String> callees = new HashSet<>();
            Matcher c = CALL.matcher(body);
            while (c.find()) {
                String callee = c.group(c.group(1) != null ? 1 : 2);
                if (bodies.containsKey(callee) && !callee.equals(e.getKey())) {
                    callees.add(callee);
                }
            }
            methods.put(e.getKey(), new Method(e.getKey(), publics.get(e.getKey()), body, callees));
        }
        return methods;
    }

    private static int matchingBrace(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("unbalanced braces from " + open);
    }

    /** Comments and string literals name methods without calling them. */
    private static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                out.append("\"\"");
                i = end < 0 ? src.length() : end + 3;
            } else if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                i = end < 0 ? src.length() : end;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 2;
            } else if (src.charAt(i) == '"' || src.charAt(i) == '\'') {
                char quote = src.charAt(i);
                int j = i + 1;
                while (j < src.length() && src.charAt(j) != quote) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(quote).append(quote);
                i = j + 1;
            } else {
                out.append(src.charAt(i++));
            }
        }
        return out.toString();
    }
}
