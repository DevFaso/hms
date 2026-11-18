package com.example.hms.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** The shared imaging DTO is an export contract, not a preview-only transport. */
class ImagingConclusionCompletenessGuardTest {
    @Test
    void bothImagingConclusionProducersCarryFullTextToEveryPdfConsumer() throws Exception {
        Path main = Path.of("src/main/java/com/example/hms");
        String source = Files.readString(main.resolve("service/impl/ChartReviewServiceImpl.java"));
        assertThat(source).contains(".reportImpression(conclusion != null ? conclusion.getImpression() : null)",
            ".impression(r.getImpression())");
        try (var files = Files.walk(main)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(file));
                if (file.getFileName().toString().contains("Pdf")) {
                    assertThat(abridgesImaging(text)).as(file.toString()).isFalse();
                }
            }
        }
        assertThat(abridgesImaging("truncatePreview(report.getImpression())")).isTrue();
        assertThat(abridgesImaging("report.getImpression().substring(0, 200)")).isTrue();
        assertThat(abridgesImaging("t.get(\"impression\", report.getImpression())")).isFalse();
    }

    private static boolean abridgesImaging(String source) {
        return java.util.regex.Pattern.compile("(?:truncate\\w*|abbreviate)\\([^;]*getImpression\\(\\)|getImpression\\(\\)\\s*\\.substring\\(",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(source).find();
    }
}
