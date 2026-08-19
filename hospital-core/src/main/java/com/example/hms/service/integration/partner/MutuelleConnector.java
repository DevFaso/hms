package com.example.hms.service.integration.partner;

import com.example.hms.service.integration.health.IntegrationHealthRecorder;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import org.springframework.stereotype.Component;

/**
 * Mutuelle health-insurance (Burkina Faso / Côte d'Ivoire / regional
 * variants) connector stub (MVP-c batch — MVP-3b). Real protocol drops
 * in once partner specs land.
 */
@Component
public class MutuelleConnector extends StubPartnerConnector {

    public MutuelleConnector(IntegrationHealthRecorder recorder,
                             IntegrationMessageRecorder messageRecorder) {
        super(recorder, messageRecorder);
    }

    @Override
    public String integrationId() {
        return "partner.mutuelle";
    }
}
