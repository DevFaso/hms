package com.example.hms.payload.event;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.UUID;

@Value
@Builder
public class EmpiEventPayload {
    String eventType;
    String empiNumber;
    UUID masterIdentityId;
    UUID patientId;
    String primaryEmpiNumber;
    String secondaryEmpiNumber;
    String fhirBundleJson;
    OffsetDateTime occurredAt;
    UUID organizationId;
    UUID hospitalId;
    UUID departmentId;
}
