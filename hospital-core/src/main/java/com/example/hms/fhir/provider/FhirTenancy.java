package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.OperationOutcome;

import java.util.UUID;

/**
 * The read-side tenant anchor shared by the FHIR providers, mirroring the
 * write services and {@code Patient/{id}/$everything}: no active hospital
 * scope means no answer — a super-admin must pin {@code X-Hospital-Id},
 * exactly as on the write path.
 */
final class FhirTenancy {

    private FhirTenancy() {}

    static UUID requireHospitalScope(String resourceType) {
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        if (hospitalId == null) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.FORBIDDEN)
                .setDiagnostics("FHIR " + resourceType + " reads require an active hospital "
                    + "scope; supply X-Hospital-Id or authenticate as a hospital-scoped user.");
            throw new ForbiddenOperationException("An active hospital scope is required.", outcome);
        }
        return hospitalId;
    }
}
