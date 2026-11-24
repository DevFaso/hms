package com.example.hms.architecture;

import com.example.hms.service.notification.PatientRecipientChallengeService;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** Every security-code response exposes a safe hint, never a raw recipient or a code. */
class RecipientVerificationFeedbackGuardTest {
    @Test
    void theWholeResponseContractIsMetadataAndAMaskedHint() {
        assertThat(PatientRecipientChallengeService.View.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("channel", "verified", "expiresAt", "maskedRecipient");
    }

    @Test
    void allStatusRequestAndConfirmResponsesGoThroughTheSameMask() throws Exception {
        String source = SourceScan.stripComments(Files.readString(Path.of(
            "src/main/java/com/example/hms/service/notification/PatientRecipientChallengeService.java")));
        assertThat(source).contains("PatientContactMask.of(prepared.recipient(), channel)",
            "PatientContactMask.of(recipient(patient, channel), channel)");
        assertThat(source.split("PatientContactMask.of", -1)).hasSize(4);
        assertThat(source).doesNotContain("new View(channel, false, prepared.expiresAt(), prepared.recipient())",
            "new View(channel, true, null, recipient(patient, channel))");
    }
}
