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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4149 (r81): whoever withdraws a lab order tells its prescriber.
 *
 * <p>The class this guards: a path that cancels a lab order, or declares it entered in error (its
 * results then leave every list), and tells nobody. The prescriber found out by not finding the
 * result they may have acted on. Every main class that writes one of those two states calls
 * {@code LabResultReleaseNotifier.prescriberOrderWithdrawn}; a new path (an importer, a bulk
 * clean-up) fails here until it does.
 */
@DisplayName("QA D4149: every path that cancels or voids a lab order tells its prescriber")
class LabOrderWithdrawalNoticeGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** A write of one of the two withdrawn states, literal or through the transition endpoint's target. */
    private static final Pattern WITHDRAWS = Pattern.compile(
        "\\.setStatus\\(\\s*LabOrderStatus\\.(?:CANCELLED|ENTERED_IN_ERROR)\\s*\\)"
            + "|\\.status\\(\\s*LabOrderStatus\\.(?:CANCELLED|ENTERED_IN_ERROR)\\s*\\)"
            + "|public LabOrderResponseDTO transitionLabOrderStatus\\(");

    private static final String NOTICE = "prescriberOrderWithdrawn(";

    @Test
    void everyClassThatWithdrawsALabOrderTellsThePrescriber() throws IOException {
        List<String> withdrawing = new ArrayList<>();
        List<String> silent = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!WITHDRAWS.matcher(source).find()) {
                    continue;
                }
                String name = MAIN.relativize(file).toString().replace('\\', '/');
                withdrawing.add(name);
                if (!source.contains(NOTICE)) {
                    silent.add(name);
                }
            }
        }
        assertThat(withdrawing).as("the scan sees the known withdrawal paths, or it is scanning nothing")
            .contains("service/LabOrderServiceImpl.java");
        assertThat(silent)
            .as("cancels or voids a lab order without telling its prescriber (QA D4149): call "
                + "LabResultReleaseNotifier.prescriberOrderWithdrawn" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void theVoidAndTheCancellationEachTellInTheirOwnMethod() throws IOException {
        String source = Files.readString(MAIN.resolve("service/LabOrderServiceImpl.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        for (String method : List.of("public LabOrderResponseDTO voidLabOrder(",
                "public LabOrderResponseDTO transitionLabOrderStatus(")) {
            int start = source.indexOf(method);
            assertThat(start).as(method).isPositive();
            int end = source.indexOf("\n    }\n", start);
            assertThat(source.substring(start, end)).as(method + "…) tells the prescriber").contains(NOTICE);
        }
    }
}
