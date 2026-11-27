package com.example.hms.service.integration.health;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.mapper.WebhookMapper;
import com.example.hms.model.platform.WebhookDelivery;
import com.example.hms.payload.dto.platform.WebhookDeliveryResponseDTO;
import com.example.hms.service.integration.InstrumentOutboxErrorText;
import com.example.hms.service.webhook.WebhookDeliveryError;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4078 / D4303: a failure shown on a screen is a cause in the reader's
 * language - never the text of a Java exception, never the English sentence a
 * connector stored.
 */
@DisplayName("D4078 / D4303: integration and delivery failures are read in the reader's language")
class IntegrationDiagnosticTextTest {

    private static final String RAW_TIMEOUT =
        "ResourceAccessException: I/O error on POST request for \"https://198.51.100.7/hook\": Connect timed out";

    @BeforeEach
    void bundles() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
    }

    @AfterEach
    void reset() {
        MessageUtil.setMessageSource(null);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("a stored diagnostic key is a French sentence for a French reader, an English one for an English reader")
    void aStoredKeyIsReadInTheReadersLanguage() {
        String french = IntegrationDiagnosticText.localize(IntegrationDiagnosticText.NOT_CONNECTED, Locale.FRENCH);
        String english = IntegrationDiagnosticText.localize(IntegrationDiagnosticText.NOT_CONNECTED, Locale.ENGLISH);

        assertThat(french).contains("partenaire").doesNotContain("integration.diagnostic");
        assertThat(english).contains("partner").isNotEqualTo(french);
    }

    @Test
    @DisplayName("the English sentences stored before this are read through the same keys")
    void rowsStoredBeforeAreTranslatedToo() {
        String expected = IntegrationDiagnosticText.localize(IntegrationDiagnosticText.NOT_CONNECTED, Locale.FRENCH);

        assertThat(IntegrationDiagnosticText.localize(
            "Stub-mode probe — partner protocol not yet wired", Locale.FRENCH)).isEqualTo(expected);
        assertThat(IntegrationDiagnosticText.localize(
            "Connector in stub mode — partner protocol not yet wired. Drop in a real partner.nhis adapter "
                + "to enable Test connection.", Locale.FRENCH)).isEqualTo(expected);
        assertThat(IntegrationDiagnosticText.localize(
            "Probe threw IllegalStateException: partner down", Locale.FRENCH))
            .doesNotContain("IllegalStateException").doesNotContain("Probe threw");
        assertThat(IntegrationDiagnosticText.localize("Re-sync failed: boom", Locale.FRENCH))
            .doesNotContain("boom");
    }

    @Test
    @DisplayName("a partner's own answer is shown as received; nothing stays nothing")
    void somebodyElsesTextIsKept() {
        assertThat(IntegrationDiagnosticText.localize("AR: unknown patient identifier", Locale.FRENCH))
            .isEqualTo("AR: unknown patient identifier");
        assertThat(IntegrationDiagnosticText.localize(null, Locale.FRENCH)).isNull();
    }

    @Test
    @DisplayName("a connector with nothing behind it is a state of the configuration, not a failure to report")
    void notConnectedIsAConfigurationState() {
        assertThat(IntegrationDiagnosticText.isConfigurationState(IntegrationDiagnosticText.NOT_CONNECTED)).isTrue();
        assertThat(IntegrationDiagnosticText.isConfigurationState("Stub-mode probe — partner protocol not yet wired"))
            .isTrue();
        assertThat(IntegrationDiagnosticText.isConfigurationState(IntegrationDiagnosticText.PROBE_ERROR)).isFalse();
        assertThat(IntegrationDiagnosticText.isConfigurationState("HTTP 503")).isFalse();
    }

    @Test
    @DisplayName("D4078: a webhook delivery's failure is a cause with a French sentence; the exception text stays on the server")
    void aWebhookFailureIsACauseNotAnException() {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setLastError(RAW_TIMEOUT);

        WebhookDeliveryResponseDTO dto = new WebhookMapper().toDto(delivery);

        assertThat(dto.getErrorCode()).isEqualTo("TIMEOUT");
        assertThat(dto.getLastError())
            .isEqualTo(WebhookDeliveryError.TIMEOUT.describe(null, Locale.FRENCH))
            .contains("temps")
            .doesNotContain("Exception").doesNotContain("I/O error").doesNotContain("198.51.100.7");
    }

    @Test
    @DisplayName("D4078: each way a delivery fails has its cause, and a receiver's refusal names its HTTP status")
    void everyFailureShapeHasACause() {
        assertThat(WebhookDeliveryError.of(405, "Receiver returned HTTP 405"))
            .isEqualTo(WebhookDeliveryError.RECEIVER_REFUSED);
        assertThat(WebhookDeliveryError.RECEIVER_REFUSED.describe(405, Locale.FRENCH)).contains("405");
        assertThat(WebhookDeliveryError.of(503, "Receiver returned HTTP 503"))
            .isEqualTo(WebhookDeliveryError.RECEIVER_ERROR);
        assertThat(WebhookDeliveryError.of(302, "Receiver returned HTTP 302"))
            .isEqualTo(WebhookDeliveryError.UNEXPECTED_STATUS);
        assertThat(WebhookDeliveryError.of(null, "Refused before sending: not a public address"))
            .isEqualTo(WebhookDeliveryError.URL_NOT_PUBLIC);
        assertThat(WebhookDeliveryError.of(null, "Endpoint is PAUSED."))
            .isEqualTo(WebhookDeliveryError.ENDPOINT_INACTIVE);
        assertThat(WebhookDeliveryError.of(null, "ResourceAccessException: I/O error: UnknownHostException: x.test"))
            .isEqualTo(WebhookDeliveryError.HOST_NOT_FOUND);
        assertThat(WebhookDeliveryError.of(null, "ResourceAccessException: Connection refused"))
            .isEqualTo(WebhookDeliveryError.CONNECTION_REFUSED);
        assertThat(WebhookDeliveryError.of(null, "SSLHandshakeException: PKIX path building failed"))
            .isEqualTo(WebhookDeliveryError.TLS);
        assertThat(WebhookDeliveryError.of(null, "IllegalStateException: something else"))
            .isEqualTo(WebhookDeliveryError.UNREACHABLE);
        assertThat(WebhookDeliveryError.of(null, null)).isNull();
        for (WebhookDeliveryError cause : WebhookDeliveryError.values()) {
            for (Locale locale : List.of(Locale.FRENCH, Locale.ENGLISH)) {
                assertThat(cause.describe(500, locale)).as(cause + " in " + locale)
                    .isNotBlank().isNotEqualTo(cause.name());
            }
        }
    }

    @Test
    @DisplayName("D4078 (class): an instrument message's transport failure is a cause, and a refusal keeps its acknowledgement code")
    void anInstrumentOutboxFailureIsACause() {
        assertThat(InstrumentOutboxErrorText.describe("SocketTimeoutException: Read timed out", Locale.FRENCH))
            .contains("temps").doesNotContain("Exception");
        assertThat(InstrumentOutboxErrorText.describe("ConnectException: Connection refused", Locale.FRENCH))
            .doesNotContain("Exception").doesNotContain("Connection refused");
        assertThat(InstrumentOutboxErrorText.describe("Negative acknowledgement: MSA|AE|1", Locale.FRENCH))
            .contains("MSA|AE|1").doesNotContain("Negative acknowledgement");
        assertThat(InstrumentOutboxErrorText.describe("Not sent: the lab order was cancelled", Locale.FRENCH))
            .contains("annulée");
        assertThat(InstrumentOutboxErrorText.describe(null, Locale.FRENCH)).isNull();
    }

    /**
     * The class, not the instance. A failure is stored as the attempt produced it
     * (the text of an exception, a connector's English sentence) and must be read
     * through a cause before it reaches a screen. The defect was each response
     * field filled straight from the stored column: {@code .lastError(d.getLastError())}.
     * Every such pass-through in the main code fails here, unless it is recorded
     * below with the reason it may stay.
     */
    @Test
    @DisplayName("D4078 (guard): no response field is filled straight from a stored failure text")
    void noResponseFieldPassesAStoredFailureTextThrough() throws IOException {
        Pattern passThrough = Pattern.compile(
            "\\.(?:lastError|lastErrorMessage|errorMessage|failureReason|lastFailureReason)\\(\\s*"
                + "(?:\\w+(?:\\(\\))?\\.)+get(?:LastError|LastErrorMessage|ErrorMessage|FailureReason|LastFailureReason)"
                + "\\(\\)\\s*\\)");
        // The insurer's own answer to an eligibility check, shown as received (QA D4322, billing lane).
        List<String> recorded = List.of("EligibilityServiceImpl.java");
        List<String> offenders = new ArrayList<>();
        Path main = Path.of("src", "main", "java", "com", "example", "hms");
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file, StandardCharsets.UTF_8);
                if (passThrough.matcher(code).find()) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertThat(offenders)
            .as("a response field is filled straight from a stored failure text: read it through a cause the "
                + "bundles have words for (IntegrationDiagnosticText, WebhookDeliveryError, "
                + "InstrumentOutboxErrorText)")
            .containsExactlyInAnyOrderElementsOf(recorded);
    }
}
