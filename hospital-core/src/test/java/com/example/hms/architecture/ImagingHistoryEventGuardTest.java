package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r44 imaging review (D1574 class): the French report drawer's status history
 * read "Report signed" and "Report authored", English text the server wrote
 * into {@code status_reason}. Every history row the server writes names an
 * {@code ImagingReportHistoryEvent}, whose code the portal translates; a bare
 * string literal as the reason would reach the reader untranslated again.
 */
@DisplayName("Imaging report history rows the server writes carry an event, not English")
class ImagingHistoryEventGuardTest {

    private static final Path SERVICE =
        Paths.get("src/main/java/com/example/hms/service/impl/ImagingReportServiceImpl.java");

    /** {@code appendStatusHistory(report, status, "literal", ...)}. */
    static final Pattern LITERAL_REASON =
        Pattern.compile("appendStatusHistory\\([^,;]++,[^,;]++,\\s*+\"");

    @Test
    void noLiteralReason() throws IOException {
        String src = Files.readString(SERVICE, StandardCharsets.UTF_8);
        assertThat(LITERAL_REASON.matcher(src).find()).isFalse();
        assertThat(src).contains("ImagingReportHistoryEvent.SIGNED.storedText()");
    }

    @Test
    void theScanSeesTheShapeTheDefectHad() {
        assertThat(LITERAL_REASON.matcher(
            "appendStatusHistory(report, ImagingReportStatus.FINAL, \"Report signed\", signer, null);").find())
            .isTrue();
        assertThat(LITERAL_REASON.matcher(
            "appendStatusHistory(report, request.getStatus(), request.getStatusReason(), changedBy, n);").find())
            .isFalse();
    }
}
