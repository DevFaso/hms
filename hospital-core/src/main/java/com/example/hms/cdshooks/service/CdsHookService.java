package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceDescriptor;

/**
 * Contract every CDS service implementation honours: a self-description
 * surfaced on the discovery endpoint and an invocation method.
 */
public interface CdsHookService {

    CdsServiceDescriptor descriptor();

    /**
     * Evaluate the hook for the hospital the CALLER acts at (QA D1920 class):
     * an order the patient has at another hospital is never a "duplicate" or
     * an "interaction" this caller is told about. {@code callerHospitalId} is
     * the controller's resolved scope; {@code null} only for a super-admin in
     * global view (see {@link CdsHookContext#hospitalScope}).
     */
    CdsHookResponse evaluate(CdsHookRequest request, java.util.UUID callerHospitalId);

    /** No caller scope: a super-admin in global view, and unit tests. */
    default CdsHookResponse evaluate(CdsHookRequest request) {
        return evaluate(request, null);
    }
}
