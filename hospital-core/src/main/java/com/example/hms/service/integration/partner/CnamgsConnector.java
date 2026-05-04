package com.example.hms.service.integration.partner;

import com.example.hms.service.integration.health.IntegrationHealthRecorder;
import org.springframework.stereotype.Component;

/**
 * CNAMGS (Gabon — Caisse Nationale d'Assurance Maladie et de Garantie
 * Sociale) connector stub (MVP-c batch — MVP-3b).
 */
@Component
public class CnamgsConnector extends StubPartnerConnector {

    public CnamgsConnector(IntegrationHealthRecorder recorder) {
        super(recorder);
    }

    @Override
    public String integrationId() {
        return "partner.cnamgs";
    }
}
