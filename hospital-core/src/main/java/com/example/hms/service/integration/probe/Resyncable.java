package com.example.hms.service.integration.probe;

import java.util.UUID;

/**
 * Re-sync SPI for partner / platform integrations (MVP-c batch — MVP-3b).
 *
 * <p>Implementing beans declare that the connector supports an explicit
 * re-sync action (typically: re-fetch reference data from the partner
 * and reconcile with local state). Connectors that have no re-sync
 * concept simply do not implement this interface — the controller
 * responds 422 in that case rather than silently no-op'ing.
 *
 * <p>Implementations should run the work asynchronously (the controller
 * itself wraps the call in {@code @Async} when calling) and use
 * {@code IntegrationHealthRecorder} to record the outcome.
 */
public interface Resyncable {

    /** The {@code integration_id} this re-sync targets — must match the recorder key. */
    String integrationId();

    /**
     * Trigger a re-sync for the given organization. Implementations must
     * call {@code IntegrationHealthRecorder.recordSuccess / recordFailure}
     * themselves — the caller does not synthesize a result.
     */
    void resync(UUID organizationId);
}
