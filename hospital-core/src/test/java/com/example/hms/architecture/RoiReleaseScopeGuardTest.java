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
                        .contains("pdf.renderForRoi(requests.copyForExport(requestId))")
                        .doesNotContain("pdf.render(");
                    // QA D4270: every release in the file is one of the two resolved copies (the staff's, by
                    // hospital, role and approver; the patient's own), never a patient id from the request.
                    int releases = endpoint.split("pdf\\.renderForRoi\\(", -1).length - 1;
                    int resolved = endpoint.split("pdf\\.renderForRoi\\(requests\\.copyForExport\\(requestId\\)\\)", -1).length - 1
                        + endpoint.split("pdf\\.renderForRoi\\(portal\\.myApprovedRoiCopy\\(auth, requestId\\)\\)", -1).length - 1;
                    assertThat(resolved).as(file + ": every renderForRoi takes a resolved approved copy").isEqualTo(releases);
                }
            }
        }
        // QA D4270: the patient's own copy is resolved from the request the patient filed, and only that.
        assertThat(source("service/impl/PatientPortalServiceImpl.java"))
            .contains("return roiRequestService.copyForSelf(findPatient(auth), requestId);");
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

    @Test
    void everyOtherSectionQueriesTheOwningHospitalRatherThanThePerformingHospital() throws Exception {
        String chart = source("service/impl/ChartReviewServiceImpl.java");
        for (String repository : new String[]{"PrescriptionRepository", "ImagingOrderRepository",
            "ProcedureOrderRepository", "NursingNoteRepository"}) {
            assertThat(source("repository/" + repository + ".java"))
                .as(repository).contains("findByPatient_IdAndHospital_IdIn");
        }
        assertThat(chart).contains("findByPatient_IdAndHospital_IdIn(patientId, readable, page)",
            "findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, readable, page)",
            "findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, readable)",
            "findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(patientId, readable)");
        assertThat(source("repository/EncounterRepository.java"))
            .contains("e.hospital.id IN :hospitalIds");
    }
}
