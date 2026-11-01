package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round 22 (setup observation): {@code POST /assignments/{id}/resend-notification}
 * accepted an assignment already verified and mailed and texted its activation
 * again. Every {@code resend…} method of production code must ask whether the
 * account or assignment is still waiting for activation in its own body
 * ({@code getConfirmationVerifiedAt()}, {@code isActive()} / {@code getActive()},
 * or the conditional claim {@code claimActivationResend}). The admin endpoint
 * refuses with a localized 409; the anonymous ones keep their neutral answer
 * (QA D1017: no account oracle) and simply send nothing.
 */
@DisplayName("An activation is re-sent only to an account still waiting for it")
class ActivationResendStateGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern RESEND_METHOD = Pattern.compile(
        "\\n    (?:(?:private|public|protected|static|final|synchronized)\\s++)++(?:[\\w<>,.?\\[\\]]++\\s++)++"
            + "(resend\\w*+)\\s*+\\((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+\\)\\s*+(?:throws [\\w., ]++)?+\\{");

    /** The activation state of the user or the assignment, not of any object (a hospital's isActive). */
    static final Pattern STATE_CHECK = Pattern.compile(
        "getConfirmationVerifiedAt\\(\\)|\\bclaimActivationResend\\("
            + "|\\b(?:[Uu]ser|[Aa]ssignment)\\w*+(?:\\.get\\(\\))?+\\.(?:isActive|getActive)\\(\\)"
            // a controller handing over to a service resend method, which the scan reads itself
            + "|\\b\\w*Service\\.resend\\w*+\\(");

    static List<String> offenders(String file, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = RESEND_METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            String body = source.substring(open, SourceScan.closingBrace(source, open) + 1);
            if (!STATE_CHECK.matcher(body).find()) {
                out.add(file + "#" + m.group(1));
            }
        }
        return out;
    }

    @Test
    @DisplayName("the scan catches the r22 resend and accepts its fix; another object's isActive does not count")
    void catchesTheRoundTwentyTwoShape() {
        String before = """
            class S {
                public void resendNotifications(UUID id) {
                    UserRoleHospitalAssignment assignment = findChangeable(id);
                    if (!assignment.getHospital().isActive()) { throw x; }
                    notifyAssignee(assignment);
                }
            }
            """;
        assertThat(offenders("S", before)).containsExactly("S#resendNotifications");
        String after = before.replace("notifyAssignee(assignment);",
            "if (assignment.getConfirmationVerifiedAt() != null) { throw y; }\n        notifyAssignee(assignment);");
        assertThat(offenders("S", after)).isEmpty();
        assertThat(offenders("S", before.replace("assignment.getHospital().isActive()", "userOpt.get().isActive()")))
            .isEmpty();
    }

    @Test
    @DisplayName("every resend method checks that the activation is still pending")
    void everyResendChecksTheActivationState() throws IOException {
        List<String> offenders = new ArrayList<>();
        int methods = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = RESEND_METHOD.matcher(source);
                while (m.find()) {
                    methods++;
                }
                offenders.addAll(offenders(file.getFileName().toString(), source));
            }
        }
        assertThat(methods).as("the scan found the resend methods").isGreaterThanOrEqualTo(5);
        assertThat(offenders)
            .as("an activation re-sent to an account already activated (QA r22)" + SourceScan.MANDATE)
            .isEmpty();
    }
}
