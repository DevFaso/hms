package com.example.hms.imaging.dicom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@link DicomProxyProperties} so the SonarCloud
 * new-code coverage gate doesn't fire on a 0%-coverage configuration
 * class (PR #349 review). Pure getter/setter carrier — no behaviour
 * to assert beyond the property defaults and round-trips.
 *
 * <p>Configuration classes are eligible for jacoco exclusion, but
 * SonarCloud applies a separate new-code threshold that the local
 * jacoco gate does not. Two test cases is enough to cover both
 * branches the property accessors expose.
 */
class DicomProxyPropertiesTest {

    @Test
    @DisplayName("Defaults: disabled + orthanc adapter + blank base-url")
    void defaultValues() {
        DicomProxyProperties props = new DicomProxyProperties();
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getAdapter()).isEqualTo("orthanc");
        assertThat(props.getBaseUrl()).isEmpty();
    }

    @Test
    @DisplayName("Setters round-trip through getters")
    void settersRoundTrip() {
        DicomProxyProperties props = new DicomProxyProperties();
        props.setEnabled(true);
        props.setAdapter("dcm4chee");
        props.setBaseUrl("https://orthanc.example.com/dicom-web");
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getAdapter()).isEqualTo("dcm4chee");
        assertThat(props.getBaseUrl()).isEqualTo("https://orthanc.example.com/dicom-web");
    }
}
