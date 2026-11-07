package com.example.hms.service.support;

import com.example.hms.repository.DeathDisputeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Hands {@link DeceasedPatientGuard}, a static choke point called from every entry point on its loaded patient, the
 * one database question it needs. As {@code MessageUtil} takes its message source. With no answer given, a recorded
 * death blocks: the guard fails closed.
 */
@Component
@RequiredArgsConstructor
public class DeathCareEligibility implements InitializingBean {

    private final DeathDisputeRepository disputes;

    @Override
    public void afterPropertiesSet() {
        DeceasedPatientGuard.useDisputes(disputes::existsOpen);
    }
}
