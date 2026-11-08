package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
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
 * QA D1086 guard (the D1046 class, round 18): a domain entity's state
 * transition refused in English. A second cancel of an OB/GYN referral
 * answered "Cannot cancel referral in terminal status CANCELLED" to a French
 * clinician: {@code ObgynReferral.cancel()} threw an English sentence that the
 * global handler passes through, while {@code GeneralReferral} threw a message
 * key. The exception-prose ratchet counted it per file, so it never failed.
 *
 * <p>Every method of an entity under {@code model/} that changes the record's
 * status ({@code this.status = …} or {@code setStatus(…)}) and refuses with an
 * exception must refuse with a message key (no spaces), never a sentence or a
 * concatenation.
 */
@DisplayName("Entity state transitions refuse with message keys")
class EntityTransitionRefusalProseGuardTest {

    private static final Path MODEL = Path.of("src/main/java/com/example/hms/model");
    private static final Pattern METHOD = Pattern.compile(
        // Possessive, one level of nested parentheses in the parameters (Sonar S8786: no backtracking).
        "\\n    (?:\\s++(?:[\\w<>\\[\\],]++\\s++)*+|(?:[\\w<>\\[\\],]++\\s++)++)(\\w++)\\(((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+)\\)"
            + "\\s*+(?:throws [\\w., ]++)?+\\{");
    private static final Pattern TRANSITION = Pattern.compile("this\\.status\\s*=|\\bstatus\\s*=\\s*\\w+Status\\.|setStatus\\(");
    private static final Pattern LIFECYCLE = Pattern.compile("@(?:PrePersist|PreUpdate|PostLoad|PostPersist|PostUpdate)\\b");
    private static final Pattern PROSE_REFUSAL = Pattern.compile(
        "throw new \\w++\\(\\s*+\"(?:[^\"\\s]*+\\s[^\"]*+\"|[^\"]*+\"\\s*+\\+)");

    @Test
    @DisplayName("no entity transition refuses in English")
    void transitionsRefuseWithKeys() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MODEL)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        List<String> offenders = new ArrayList<>();
        int transitions = 0;
        for (Path file : files) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            String entity = file.getFileName().toString().replace(".java", "");
            transitions += transitionMethods(source).size();
            offenders.addAll(offenders(entity, source));
        }
        assertThat(transitions).as("entity transition methods found").isGreaterThanOrEqualTo(5);
        assertThat(offenders)
            .as("an entity state transition refuses with an English sentence the handler sends as is (QA D1086, "
                + "D1046 class): throw a message key and add it to the three bundles" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1086 shape")
    void catchesTheOldShape() {
        String before = """
            public class ObgynReferral {
                public void cancel(String reason) {
                    if (isTerminal(this.status)) {
                        throw new IllegalStateException(
                            "Cannot cancel referral in terminal status " + this.status);
                    }
                    this.status = ObgynReferralStatus.CANCELLED;
                }
            }
            """;
        assertThat(offenders("ObgynReferral", before)).containsExactly("ObgynReferral#cancel");
        String after = before.replace("\"Cannot cancel referral in terminal status \" + this.status",
            "\"referral.r7.cannotCancelTerminal\"");
        assertThat(offenders("ObgynReferral", after)).isEmpty();
        String helper = """
            public class ObgynReferral {
                public void start() {
                    requireStatus("start", ObgynReferralStatus.ACKNOWLEDGED);
                    this.status = ObgynReferralStatus.IN_PROGRESS;
                }
                private void requireStatus(String action, ObgynReferralStatus... allowed) {
                    throw new IllegalStateException("Cannot " + action + " referral in status " + this.status);
                }
            }
            """;
        assertThat(offenders("ObgynReferral", helper)).as("a helper a transition calls").containsExactly("ObgynReferral#requireStatus");
    }

    private static List<String> transitionMethods(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (TRANSITION.matcher(body).find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    static List<String> offenders(String entity, String source) {
        // Pass 1: each method's body; pass 2: a prose refusal in a transition,
        // or in a helper a transition of the same entity calls (requireStatus).
        java.util.Map<String, String> bodies = new java.util.LinkedHashMap<>();
        java.util.Set<String> lifecycle = new java.util.HashSet<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            bodies.merge(m.group(1), source.substring(open, Math.min(close + 1, source.length())), String::concat);
            // A JPA lifecycle callback (or a non-public helper) defaults the
            // status and checks persistence invariants before a write: a
            // developer's integrity guard, not a transition a user asked for.
            String head = source.substring(Math.max(0, m.start() - 200), m.start() + 1);
            int boundary = Math.max(head.lastIndexOf('}'), head.lastIndexOf(';'));
            String annotations = head.substring(boundary + 1);
            if (LIFECYCLE.matcher(annotations).find() || !m.group().contains("public ")) {
                lifecycle.add(m.group(1));
            }
        }
        List<String> transitions = bodies.entrySet().stream()
            .filter(e -> !lifecycle.contains(e.getKey()))
            .filter(e -> TRANSITION.matcher(e.getValue()).find()).map(java.util.Map.Entry::getKey).toList();
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, String> method : bodies.entrySet()) {
            if (!PROSE_REFUSAL.matcher(method.getValue()).find()) {
                continue;
            }
            boolean reached = transitions.contains(method.getKey()) || transitions.stream()
                .anyMatch(t -> Pattern.compile("\\b" + method.getKey() + "\\(").matcher(bodies.get(t)).find());
            if (reached) {
                out.add(entity + "#" + method.getKey());
            }
        }
        return out;
    }
}
