package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PatientDuplicateReviewGuardTest {
    @Test void staffCreationRechecksAndAuditsTheDecisionInItsOwnTransactionWithoutMerging() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/example/hms/service/PatientServiceImpl.java"));
        String method = SourceScan.methodBody(source, "createPatientByStaff");
        assertThat(method).contains("duplicateReview.prepare(dto, hospitalId)", "duplicateReview.record(duplicateDecision, patient)");
        assertThat(method.indexOf("duplicateReview.prepare")).isLessThan(method.indexOf("patientRepository.save(mapped)"));
        assertThat(method.indexOf("duplicateReview.record")).isGreaterThan(method.indexOf("patientRepository.save(mapped)"));
        assertThat(SourceScan.methodBody(source, "createNewbornPatient")).doesNotContain("duplicateReview");
        String audit = Files.readString(Path.of("src/main/java/com/example/hms/service/PatientDuplicateReview.java"));
        assertThat(audit).contains("logRequiredEvent", "KEEP_SEPARATE").doesNotContain("afterCommit", "mergePatients(", "logEvent(");
    }
}
