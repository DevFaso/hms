package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.example.hms.fhir.mapper.EncounterFhirMapper;
import com.example.hms.fhir.write.EncounterFhirWriteService;
import com.example.hms.repository.EncounterRepository;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class EncounterFhirResourceProvider implements IResourceProvider {

    private final EncounterRepository encounterRepository;
    private final EncounterFhirMapper mapper;
    private final EncounterFhirWriteService writeService;

    public EncounterFhirResourceProvider(
        EncounterRepository encounterRepository,
        EncounterFhirMapper mapper,
        EncounterFhirWriteService writeService
    ) {
        this.encounterRepository = encounterRepository;
        this.mapper = mapper;
        this.writeService = writeService;
    }

    @Override
    public Class<Encounter> getResourceType() {
        return Encounter.class;
    }

    @Read
    public Encounter read(@IdParam IdType id) {
        UUID uuid = FhirIds.parseOrThrow(id);
        return encounterRepository.findById(uuid)
            .map(mapper::toFhir)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    @Search
    public List<Encounter> search(
        @OptionalParam(name = "patient") ReferenceParam patient,
        @OptionalParam(name = "subject") ReferenceParam subject,
        @OptionalParam(name = "_id") TokenParam idParam
    ) {
        if (idParam != null && idParam.getValue() != null) {
            UUID uuid = FhirIds.tryParse(idParam.getValue());
            if (uuid == null) return Collections.emptyList();
            return encounterRepository.findById(uuid).map(mapper::toFhir).map(List::of).orElseGet(Collections::emptyList);
        }
        UUID patientId = FhirIds.fromReference(patient != null ? patient : subject);
        if (patientId == null) return Collections.emptyList();
        return encounterRepository.findByPatient_Id(patientId).stream()
            .map(mapper::toFhir)
            .toList();
    }

    /**
     * PUT /Encounter/{id}. Updates the FHIR-mutable subset
     * ({@code period.end} + {@code reasonCode[0].text}) of an existing
     * encounter at the active hospital scope.
     *
     * <p>Feature-flagged: when {@code app.fhir.write.enabled=false}
     * (default) the handler returns 405 <strong>before</strong> any
     * request-shape validation, matching the contract documented in
     * the {@code fhir-r4-api} skill (caught on the Patient provider
     * during PR #343 review).
     */
    @Update
    public MethodOutcome update(
        @IdParam IdType id,
        @ResourceParam Encounter resource,
        RequestDetails requestDetails
    ) {
        if (!writeService.isEnabled()) {
            throw new MethodNotAllowedException(
                "FHIR write API is disabled — set app.fhir.write.enabled=true to opt in."
            );
        }
        if (resource == null) {
            throw unprocessable(
                "PUT /Encounter/{id} requires an Encounter resource body.",
                OperationOutcome.IssueType.STRUCTURE
            );
        }
        UUID uuid = FhirIds.parseOrThrow(id);
        if (resource.getIdElement() != null && resource.getIdElement().getIdPart() != null
            && !resource.getIdElement().getIdPart().isBlank()
            && !resource.getIdElement().getIdPart().equals(uuid.toString())) {
            throw unprocessable(
                "Resource.id does not match the URL id; refusing to honor PUT against a mismatched id.",
                OperationOutcome.IssueType.BUSINESSRULE
            );
        }
        // Row-20 follow-on: read the If-Match header (optional). HAPI
        // doesn't expose a dedicated parameter binding for this so we
        // pull it off RequestDetails; absent / blank header skips the
        // precondition (foundation behaviour: last-write-wins).
        String ifMatch = requestDetails == null ? null : requestDetails.getHeader("If-Match");
        com.example.hms.model.Encounter saved = writeService.update(uuid, resource, ifMatch);
        Encounter responseFhir = mapper.toFhir(saved);
        // Stamp meta.versionId so HAPI renders an ETag the client can
        // round-trip on the next If-Match. Same encoding as the service:
        // updatedAt-epoch-millis. The IdType in the response carries
        // the version part so client SDKs that read it via
        // resource.getMeta().getVersionId() get the same value.
        String versionId = EncounterFhirWriteService.toVersionId(saved.getUpdatedAt());
        responseFhir.getMeta().setVersionId(versionId);
        return new MethodOutcome()
            .setId(new IdType("Encounter", saved.getId().toString(), versionId))
            .setResource(responseFhir);
    }

    private static UnprocessableEntityException unprocessable(String message, OperationOutcome.IssueType type) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(type)
            .setDiagnostics(message);
        return new UnprocessableEntityException(message, outcome);
    }
}
