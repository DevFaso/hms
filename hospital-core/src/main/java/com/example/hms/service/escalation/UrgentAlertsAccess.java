package com.example.hms.service.escalation;

import com.example.hms.service.maternity.ObstetricAlertEscalationService;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Scorecard K4: how a service that files urgent alerts reaches the escalation service. Each such service owns one
 * (not a bean: nothing is scanned or wired for it) and hands it the context's provider from an {@code @Autowired}
 * setter.
 *
 * <p>Resolved when used, never at wiring: a context without the escalation service (a test slice) gets
 * {@code null} here and each use falls back as when the service is absent, where an optional {@code @Lazy}
 * injection point gave a never-null proxy that failed on first use. A unit test's escalation service, when one
 * is set, is used in place of the context's.
 */
public final class UrgentAlertsAccess {

    private ObstetricAlertEscalationService service;
    private ObjectProvider<ObstetricAlertEscalationService> provider;

    /** The context's escalation service, looked up on each use. */
    public void useProvider(ObjectProvider<ObstetricAlertEscalationService> provider) {
        this.provider = provider;
    }

    /** A unit test's escalation service, used in place of the context's. */
    public void useService(ObstetricAlertEscalationService service) {
        this.service = service;
    }

    /** The escalation service, or {@code null} when there is none: the caller then files nothing. */
    public ObstetricAlertEscalationService get() {
        if (service != null) {
            return service;
        }
        return provider == null ? null : provider.getIfAvailable();
    }
}
