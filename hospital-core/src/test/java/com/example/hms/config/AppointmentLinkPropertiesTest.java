package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link AppointmentLinkProperties} so the New Code coverage gate
 * stays satisfied for PR #315 and the normalisation contract (always
 * {@code "/.../"} shape) is regression-tested.
 */
@DisplayName("AppointmentLinkProperties (PR #315)")
class AppointmentLinkPropertiesTest {

    @Test
    @DisplayName("defaults — produce the expected /.../ shape after normalize()")
    void defaults_normalisedShape() {
        AppointmentLinkProperties props = new AppointmentLinkProperties();
        props.normalize();
        assertThat(props.getReschedulePath()).isEqualTo("/appointments/reschedule/");
        assertThat(props.getCancelPath()).isEqualTo("/appointments/cancel/");
    }

    @Test
    @DisplayName("setters — operator-supplied values without leading/trailing slash get normalised")
    void normalisesOperatorOverrides() {
        AppointmentLinkProperties props = new AppointmentLinkProperties();
        props.setReschedulePath("custom/reschedule");
        props.setCancelPath("//custom/cancel//");
        props.normalize();
        assertThat(props.getReschedulePath()).isEqualTo("/custom/reschedule/");
        assertThat(props.getCancelPath()).isEqualTo("/custom/cancel/");
    }

    @Test
    @DisplayName("setters — null / blank values fall back to the documented defaults")
    void blankOverrideFallsBackToDefault() {
        AppointmentLinkProperties props = new AppointmentLinkProperties();
        props.setReschedulePath(null);
        props.setCancelPath("   ");
        props.normalize();
        assertThat(props.getReschedulePath()).isEqualTo("/appointments/reschedule/");
        assertThat(props.getCancelPath()).isEqualTo("/appointments/cancel/");
    }

    @Test
    @DisplayName("normalize — is idempotent")
    void normalise_idempotent() {
        AppointmentLinkProperties props = new AppointmentLinkProperties();
        props.setReschedulePath("foo/bar");
        props.normalize();
        String once = props.getReschedulePath();
        props.normalize();
        assertThat(props.getReschedulePath()).isEqualTo(once);
    }
}
