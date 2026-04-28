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

    CdsHookResponse evaluate(CdsHookRequest request);
}
