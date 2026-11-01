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

    static final Pattern STATE_CHECK = Pattern.compile(
        "getConfirmationVerifiedAt\\(\\)|\\.isActive\\(\\)|\\.getActive\\(\\)|\\bclaimActivationResend\\("
            // a controller handing over to a service resend method, which the scan reads itself
            + "|\\b\\w*Service\\.resend\\w*+\\(");

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
                    int open = m.end() - 1;
                    String body = source.substring(open, SourceScan.closingBrace(source, open) + 1);
                    if (!STATE_CHECK.matcher(body).find()) {
                        offenders.add(file.getFileName() + "#" + m.group(1));
                    }
                }
            }
        }
        assertThat(methods).as("the scan found the resend methods").isGreaterThanOrEqualTo(5);
        assertThat(offenders)
            .as("an activation re-sent to an account already activated (QA r22)" + SourceScan.MANDATE)
            .isEmpty();
    }
}
