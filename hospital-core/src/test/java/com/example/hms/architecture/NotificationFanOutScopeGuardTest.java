package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
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
 * QA D1610 guard: hospital A's announcement was pushed to every staff member of every hospital,
 * because {@code AnnouncementServiceImpl} picked its recipients with {@code findByIsDeletedFalse()}.
 *
 * <p>Two rules, over every class under {@code src/main/java}:</p>
 * <ol>
 *   <li>A class that sends a notification or an In-Basket item never picks its recipients with a
 *       lookup that spans every hospital ({@link #PLATFORM_WIDE_LOOKUPS}). The one allowance is the
 *       super-admin's platform-wide announcement, and it must sit beside its hospital branch.</li>
 *   <li>A WebSocket broadcast ({@code convertAndSend}, not {@code convertAndSendToUser}) goes to a
 *       hospital's own topic ({@code ... + hospitalId}), or is one of the {@link #ALLOWED_BROADCASTS}.</li>
 * </ol>
 * Both allowances may only shrink.
 */
class NotificationFanOutScopeGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** A class that delivers something to a person. */
    private static final Pattern SENDS = Pattern.compile(
        "createNotification\\(|createLiteralNotification\\(|sendNotification\\(|createItem(?:InNewTransaction)?\\(");

    /** Recipient lookups that cross every hospital. */
    private static final Pattern PLATFORM_WIDE_LOOKUPS = Pattern.compile(
        "\\.(findByIsDeletedFalse|findLiveAssignmentsWithRoleCodes|findLiveStaffUsersPlatformWide)\\("
            + "|[uU]serRepository\\.findAll\\(|[sS]taffRepository\\.findAll\\(");

    private static final Pattern BROADCAST = Pattern.compile("\\.convertAndSend\\(\\s*+([^,]++),");

    private static final Pattern METHOD = Pattern.compile(
        "^\\s++(?:public|private|protected)\\s++(?:(?:static|final|abstract|synchronized)\\s++)*+"
            + "(?:<(?:[^<>]++|<[^<>]*+>)*+>\\s++)?"
            + "(?:[\\w.<>\\[\\]?]++|,\\s*+)++\\s++(\\w++)\\(");

    /** Platform-wide recipient lookups in a sending class. Shrink only. */
    private static final Map<String, String> ALLOWED_LOOKUPS = Map.of(
        "AnnouncementServiceImpl#createAnnouncement",
        "a super-admin's platform-wide announcement (global view, no hospital); a hospital's goes to its staff");

    /** Broadcast destinations every connected client receives. Shrink only. */
    private static final Map<String, String> ALLOWED_BROADCASTS = Map.of(
        "EmergencyControlServiceImpl#BROADCAST_TOPIC", "the super-admin emergency banner, system-wide by design",
        "NotificationWebSocketController#\"/topic/notifications\"",
        "fallback for a notification with no recipient; no hospital-owned sender leaves the recipient blank (tasklist)");

    @Test
    @DisplayName("QA D1610: a notification never picks its recipients across every hospital")
    void sendersNeverFanOutPlatformWide() throws IOException {
        List<String> offending = new ArrayList<>();
        Set<String> seenAllowed = new TreeSet<>();
        int senders = 0;
        for (Path file : javaFiles()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (file.toString().replace('\\', '/').contains("/repository/") || !SENDS.matcher(source).find()) {
                continue;
            }
            senders++;
            List<String> lines = source.lines().toList();
            String owner = file.getFileName().toString().replace(".java", "");
            for (int i = 0; i < lines.size(); i++) {
                if (!PLATFORM_WIDE_LOOKUPS.matcher(lines.get(i)).find()) {
                    continue;
                }
                String key = owner + "#" + enclosingMethod(lines, i);
                String window = String.join("\n", lines.subList(Math.max(0, i - 3), Math.min(lines.size(), i + 3)));
                if (ALLOWED_LOOKUPS.containsKey(key) && window.contains("findLiveStaffUsersAtHospital(hospitalId)")
                        && lines.get(i).contains("findLiveStaffUsersPlatformWide(")) {
                    seenAllowed.add(key);
                    continue;
                }
                offending.add(key + " (line " + (i + 1) + "): " + lines.get(i).trim());
            }
        }
        assertThat(senders).as("the scan found the notification senders").isGreaterThanOrEqualTo(20);
        assertThat(offending).as("notification senders that pick recipients across every hospital").isEmpty();
        assertThat(seenAllowed).as("a stale allowance must be removed")
            .containsExactlyInAnyOrderElementsOf(ALLOWED_LOOKUPS.keySet());
    }

    @Test
    @DisplayName("QA D1610: the platform-wide staff lookup is used by the announcement alone")
    void platformWideStaffLookupHasOneCaller() throws IOException {
        List<String> callers = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (file.toString().replace('\\', '/').contains("/repository/")) {
                continue;
            }
            if (Files.readString(file, StandardCharsets.UTF_8).contains("findLiveStaffUsersPlatformWide(")) {
                callers.add(file.getFileName().toString());
            }
        }
        assertThat(callers).containsExactly("AnnouncementServiceImpl.java");
    }

    @Test
    @DisplayName("QA D1610: a WebSocket broadcast goes to one hospital's topic, or is an allowed system-wide one")
    void broadcastsAreHospitalTopics() throws IOException {
        List<String> offending = new ArrayList<>();
        Set<String> seenAllowed = new TreeSet<>();
        int broadcasts = 0;
        for (Path file : javaFiles()) {
            String owner = file.getFileName().toString().replace(".java", "");
            Matcher m = BROADCAST.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                broadcasts++;
                String destination = m.group(1).trim();
                String key = owner + "#" + destination;
                if (ALLOWED_BROADCASTS.containsKey(key)) {
                    seenAllowed.add(key);
                } else if (!destination.matches(".*\\+\\s*+\\w*[hH]ospitalId")) {
                    offending.add(key);
                }
            }
        }
        assertThat(broadcasts).as("the scan found the broadcasts").isGreaterThanOrEqualTo(3);
        assertThat(offending).as("broadcasts to a destination every hospital's clients receive").isEmpty();
        assertThat(seenAllowed).as("a stale allowance must be removed")
            .containsExactlyInAnyOrderElementsOf(ALLOWED_BROADCASTS.keySet());
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String enclosingMethod(List<String> lines, int from) {
        for (int i = from; i >= 0; i--) {
            Matcher m = METHOD.matcher(lines.get(i));
            if (m.find()) {
                return m.group(1);
            }
        }
        return "?";
    }
}
