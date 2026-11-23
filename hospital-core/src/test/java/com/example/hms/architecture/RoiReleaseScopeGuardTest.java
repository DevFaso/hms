package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** A release is hospital-owned information, never the actor's treatment/break-glass view. */
class RoiReleaseScopeGuardTest {
    private String source(String path) throws Exception {
        return SourceScan.stripComments(Files.readString(Path.of("src/main/java/com/example/hms/" + path)));
    }

    @Test
    void everyRoiPdfUsesTheDedicatedHospitalReleaseViews() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/com/example/hms"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String endpoint = SourceScan.stripComments(Files.readString(file));
                if (endpoint.contains("/roi-requests/") && endpoint.contains("record.pdf")) {
                    assertThat(endpoint).as(file.toString())
                        .contains("pdf.renderForRoi(requests.copyForExport(requestId), requestId)")
                        .doesNotContain("pdf.render(");
                }
            }
        }
        String pdf = source("service/PatientRecordPdfService.java");
        assertThat(pdf).contains("code.getStatus() == null && !hasDirectives", "if (code.getStatus() != null)");
        assertThat(pdf).contains("requestId == null", "getHospitalReleaseChart(patientId, hospitalId)",
            "getHospitalReleaseStoryboard(patientId, hospitalId)", "requestId == null ? patient.getAllergies() : null");
        for (String name : new String[]{"ChartReviewServiceImpl", "PatientStoryboardServiceImpl"}) {
            String aggregator = source("service/impl/" + name + ".java");
            assertThat(aggregator).contains("release ? Set.of(hospitalId)", "!release && readable != null");
        }
        assertThat(source("service/impl/ChartReviewServiceImpl.java"))
            .contains("if (!release && hospitalId != null)", "hospitalId.equals(orderingHospitalIdOf(r))");
        assertThat(source("service/impl/PatientStoryboardServiceImpl.java"))
            .contains("release ? allergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)",
                "release ? null : patient.getCodeStatus()");
    }
}
