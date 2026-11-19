package com.example.hms.imaging.dicom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SSRF fence on the PACS URL a hospital admin types. */
class PacsUrlPolicyTest {

    @Test
    @DisplayName("a public https address passes; trailing slashes are dropped")
    void publicHttpsPasses() {
        assertThat(PacsUrlPolicy.normalise(" https://93.184.216.34/dicom-web// ")).isEqualTo("https://93.184.216.34/dicom-web");
        assertThatCode(() -> PacsUrlPolicy.requireCallable("https://93.184.216.34/dicom-web", false))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://127.0.0.1/dicom-web",
        "https://10.0.0.5/dicom-web",
        "https://172.16.4.4/dicom-web",
        "https://192.168.1.10/dicom-web",
        "https://169.254.169.254/latest",
        "https://100.64.0.9/dicom-web",
        "https://0.0.0.0/dicom-web",
        "https://[::1]/dicom-web",
        "https://[fd00::1]/dicom-web",
        "https://localhost/dicom-web"
    })
    @DisplayName("an internal address is refused in production")
    void internalRefused(String url) {
        assertThatThrownBy(() -> PacsUrlPolicy.requireCallable(url, false))
            .isInstanceOfSatisfying(PacsUrlPolicy.RejectedException.class,
                ex -> assertThat(ex.messageKey()).isEqualTo(PacsUrlPolicy.PRIVATE_HOST));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)",
        "file:///etc/passwd",
        "ftp://93.184.216.34/x",
        "https://user:pw@93.184.216.34/dicom-web",
        "https://93.184.216.34/dicom-web?x=1",
        "https://93.184.216.34/dicom-web#frag",
        "not a url"
    })
    @DisplayName("a non-web, credentialed or decorated address is refused")
    void malformedRefused(String url) {
        assertThatThrownBy(() -> PacsUrlPolicy.requireCallable(url, false))
            .isInstanceOfSatisfying(PacsUrlPolicy.RejectedException.class,
                ex -> assertThat(ex.messageKey()).isEqualTo(PacsUrlPolicy.INVALID));
    }

    @Test
    @DisplayName("http is refused in production and allowed for local development")
    void httpOnlyInDev() {
        assertThatThrownBy(() -> PacsUrlPolicy.requireCallable("http://93.184.216.34/dicom-web", false))
            .isInstanceOfSatisfying(PacsUrlPolicy.RejectedException.class,
                ex -> assertThat(ex.messageKey()).isEqualTo(PacsUrlPolicy.HTTPS_REQUIRED));
        assertThatCode(() -> PacsUrlPolicy.requireCallable("http://localhost:8042/dicom-web", true))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DICOM UID syntax")
    void uidSyntax() {
        assertThat(DicomUid.isValid("1.2.840.10008.5.1.4.1.1.2")).isTrue();
        assertThat(DicomUid.isValid("1..2")).isFalse();
        assertThat(DicomUid.isValid("1.2/../3")).isFalse();
        assertThat(DicomUid.isValid("1.2." + "9".repeat(70))).isFalse();
        assertThat(DicomUid.isValid(null)).isFalse();
    }
}
