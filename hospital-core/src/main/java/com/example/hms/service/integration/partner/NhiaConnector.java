package com.example.hms.service.integration.partner;

import com.example.hms.service.integration.health.IntegrationHealthRecorder;
import org.springframework.stereotype.Component;

/**
 * NHIA (Nigeria / similar national insurance authority) connector stub
 * (MVP-c batch — MVP-3b).
 */
@Component
public class NhiaConnector extends StubPartnerConnector {

    public NhiaConnector(IntegrationHealthRecorder recorder) {
        super(recorder);
    }

    @Override
    public String integrationId() {
        return "partner.nhia";
    }
}
