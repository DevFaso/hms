package com.example.hms.config;

import com.example.hms.service.support.UrlPathNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * URL path fragments used to construct appointment-confirmation email
 * links of the form {@code frontendBaseUrl + path + appointmentId}.
 *
 * <p>Lives in a shared bean so {@code AppointmentServiceImpl} and
 * {@code PatientPortalServiceImpl} (and any future caller) read the same
 * normalised value. Sonar S1075 + PR #315 review — externalising the two
 * fragments without a shared owner caused {@code @Value} blocks and a
 * normalisation hook to be duplicated across both services.
 *
 * <p>Defaults preserve the pre-existing hard-coded values so no ops
 * action is required at merge. Both values are normalised via
 * {@link UrlPathNormalizer#fragment} after binding so writes like
 * {@code "appointments/reschedule"}, {@code "/appointments/reschedule/"},
 * or {@code "//appointments/reschedule//"} all yield the same shape
 * and the downstream concatenation can't silently produce a broken URL.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.frontend.appointments")
public class AppointmentLinkProperties {

    private String reschedulePath = "/appointments/reschedule/";
    private String cancelPath = "/appointments/cancel/";

    @PostConstruct
    void normalize() {
        reschedulePath = UrlPathNormalizer.fragment(reschedulePath, "/appointments/reschedule/");
        cancelPath = UrlPathNormalizer.fragment(cancelPath, "/appointments/cancel/");
    }
}
