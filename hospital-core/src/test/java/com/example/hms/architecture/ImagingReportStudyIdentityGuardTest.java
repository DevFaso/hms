package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3000 (r67): the signed report showed « Modalité » empty and « Région du
 * corps — » while its order said Radiographie / Thorax, because the screen read
 * the report row's own columns, blank when its author left them so. A report's
 * modality and body region are read through
 * {@code ImagingReportMapper#studyModalityOf} / {@code #studyBodyRegionOf},
 * which fall back to the order's.
 *
 * <p>A main class that reads {@code getModality()} or {@code getBodyRegion()}
 * on a report variable fails here unless it is listed with the reason.
 */
class ImagingReportStudyIdentityGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** {@code report.getModality()}, {@code src.getBodyRegion()}, {@code imagingReport.getModality()}... */
    private static final Pattern REPORT_READ = Pattern.compile("\\b(\\w++)\\.get(?:Modality|BodyRegion)\\(\\)");

    private static final Map<String, String> ALLOWED = Map.of(
        "com/example/hms/mapper/ImagingReportMapper.java", "the fallback itself",
        "com/example/hms/service/impl/ImagingReportServiceImpl.java",
        "the signature payload attests to the stored columns, which the mapper fills before signing"
    );

    @Test
    void reportModalityAndRegionAreReadWithTheOrdersFallback() throws IOException {
        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (ALLOWED.containsKey(rel)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("ImagingReport")) {
                    continue;
                }
                Matcher m = REPORT_READ.matcher(source);
                while (m.find()) {
                    String receiver = m.group(1);
                    if ("src".equals(receiver) || receiver.toLowerCase(java.util.Locale.ROOT).contains("report")) {
                        offenders.add(rel + ": " + m.group());
                    }
                }
            }
        }
        assertThat(offenders)
            .as("Read a report's modality/region through ImagingReportMapper.studyModalityOf/studyBodyRegionOf (QA D3000)")
            .isEmpty();
    }

    @Test
    void theResponseUsesTheFallback() throws IOException {
        String mapper = Files.readString(MAIN.resolve("com/example/hms/mapper/ImagingReportMapper.java"),
            StandardCharsets.UTF_8);
        assertThat(mapper).contains(".modality(studyModalityOf(report))", ".bodyRegion(studyBodyRegionOf(report))");
    }
}
