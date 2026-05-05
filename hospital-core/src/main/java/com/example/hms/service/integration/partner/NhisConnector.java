package com.example.hms.service.integration.partner;

import com.example.hms.service.integration.health.IntegrationHealthRecorder;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import org.springframework.stereotype.Component;

/**
 * NHIS (Ghana / similar national insurance scheme) connector stub
 * (MVP-c batch — MVP-3b). Real protocol drops in via subclass override.
 */
@Component
public class NhisConnector extends StubPartnerConnector {

    public NhisConnector(IntegrationHealthRecorder recorder,
                         IntegrationMessageRecorder messageRecorder) {
        super(recorder, messageRecorder);
    }

    @Override
    public String integrationId() {
        return "partner.nhis";
    }
}
