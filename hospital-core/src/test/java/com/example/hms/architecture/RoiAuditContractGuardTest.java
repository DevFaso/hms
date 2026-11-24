package com.example.hms.architecture;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DisclosureCategory;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogServiceImpl;
import com.example.hms.service.roi.RoiRequestService;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Workflow commits and download accounting must agree on every ROI entry point. */
class RoiAuditContractGuardTest {
    private static final Path ROOT = Path.of("src/main/java/com/example/hms");
    private String source(String relative) throws Exception {
        return SourceScan.stripComments(Files.readString(ROOT.resolve(relative)));
    }

    @Test
    void everyWorkflowAuditUsesTheMandatoryTransactionPort() throws Exception {
        String workflow = source("service/roi/RoiRequestService.java");
        assertThat(workflow).contains("RequiredAuditEventLogPort auditService", "auditService.logRequiredEvent(")
            .doesNotContain("auditService.logEvent(");
        for (var method : RoiRequestService.class.getDeclaredMethods()) {
            if (List.of("create", "createForSelf", "fulfil", "deny", "cancel").contains(method.getName())) {
                assertThat(method.getAnnotation(Transactional.class)).as(method.getName()).isNotNull();
                assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRED);
            }
        }
        assertThat(AuditEventLogServiceImpl.class.getMethod("logRequiredEvent", AuditEventRequestDTO.class)
            .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        try (var files = Files.walk(ROOT)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String s = SourceScan.stripComments(Files.readString(p));
                if (s.matches("(?s).*AuditEventType\\.ROI_(REQUESTED|FULFILLED|DENIED|CANCELLED).*")) {
                    assertThat(p).as("all workflow publishers are covered").isEqualTo(ROOT.resolve("service/roi/RoiRequestService.java"));
                }
            }
        }
    }

    @Test
    void everyReleaseDownloadUsesARetrievalEventAndPreservesTreatmentAccounting() throws Exception {
        assertThat(source("service/PatientRecordPdfService.java"))
            .contains("requestId == null ? AuditEventType.PATIENT_EXPORT : AuditEventType.DATA_ACCESS",
                "requestId == null ? null : patient.getId()", "requestId != null && persisted == null");
        assertThat(DisclosureCategory.accountableEventTypes()).doesNotContain(AuditEventType.DATA_ACCESS)
            .contains(AuditEventType.PATIENT_EXPORT);
        assertThat(DisclosureCategory.classify(AuditEventType.DATA_ACCESS, "ROI_REQUEST")).isNull();
    }

    @Test
    void everyEmptyReleaseSectionHasNeutralWordingAndAnExplicitApprovedScope() throws Exception {
        assertThat(source("service/PatientRecordPdfService.java"))
            .contains("render(copy.patientId(), copy.requestId(), copy.hospitalId())",
                "!hospitalId.equals(approvedHospitalId)", "release ? labels.get(\"pdf.record.notIncluded\") : treatmentText",
                "pages.none(t.get(\"pdf.record.codeStatus.none\"))", "pages.none(t.get(\"pdf.record.encounter.none\"))",
                "none(labels.get(\"pdf.record.none\"))", "pdf.record.releaseScope");
        assertThat(source("controller/RoiRecordExportController.java"))
            .contains("@PreAuthorize(PatientRecordExportController.EXPORT_ROLES)",
                "pdf.renderForRoi(requests.copyForExport(requestId))");
    }
}
