package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.DocumentReferenceFhirMapper;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientUploadedDocumentRepository;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FHIR R4 {@code DocumentReference} provider (Tier 2 item 44), sourced from
 * the two document domains:
 * <ul>
 *   <li>{@code upl-{uuid}} — patient-uploaded documents (metadata + public
 *       URL; soft-deleted rows never appear);</li>
 *   <li>{@code discharge-{uuid}} — discharge summaries, rendered inline as
 *       {@code text/plain} attachments.</li>
 * </ul>
 *
 * <p>Read-only. Tenant contract matches the other providers — scope
 * required, foreign rows collapse to not-found. Discharge summaries carry
 * their own hospital column; uploaded documents are patient-anchored with
 * no hospital column, so their gate is the patient's registration at the
 * caller's hospital (the same gate {@code Patient/{id}/$everything} uses).
 */
@Component
// Read-only TX: open-in-view=false, so the mapper's lazy walks after the
// repository call would otherwise throw LazyInitializationException.
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class DocumentReferenceFhirResourceProvider implements IResourceProvider {

    private static final int MAX_PER_PATIENT = 200;
    private static final String UPLOAD_PREFIX = "upl-";
    private static final String DISCHARGE_PREFIX = "discharge-";

    private final PatientUploadedDocumentRepository uploadedDocumentRepository;
    private final DischargeSummaryRepository dischargeSummaryRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final DocumentReferenceFhirMapper mapper;

    public DocumentReferenceFhirResourceProvider(
        PatientUploadedDocumentRepository uploadedDocumentRepository,
        DischargeSummaryRepository dischargeSummaryRepository,
        PatientHospitalRegistrationRepository registrationRepository,
        DocumentReferenceFhirMapper mapper
    ) {
        this.uploadedDocumentRepository = uploadedDocumentRepository;
        this.dischargeSummaryRepository = dischargeSummaryRepository;
        this.registrationRepository = registrationRepository;
        this.mapper = mapper;
    }

    @Override
    public Class<DocumentReference> getResourceType() {
        return DocumentReference.class;
    }

    @Read
    public DocumentReference read(@IdParam IdType id) {
        if (id == null || id.getIdPart() == null) {
            throw new ResourceNotFoundException(id);
        }
        UUID hospitalId = FhirTenancy.requireHospitalScope("DocumentReference");
        String idPart = id.getIdPart();
        if (idPart.startsWith(UPLOAD_PREFIX)) {
            UUID uuid = FhirIds.tryParse(idPart.substring(UPLOAD_PREFIX.length()));
            if (uuid == null) throw new ResourceNotFoundException(id);
            return uploadedDocumentRepository.findById(uuid)
                .filter(d -> d.getDeletedAt() == null)
                .filter(d -> patientVisibleAt(d.getPatient() != null ? d.getPatient().getId() : null, hospitalId))
                .map(mapper::toFhir)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        }
        if (idPart.startsWith(DISCHARGE_PREFIX)) {
            UUID uuid = FhirIds.tryParse(idPart.substring(DISCHARGE_PREFIX.length()));
            if (uuid == null) throw new ResourceNotFoundException(id);
            return dischargeSummaryRepository.findById(uuid)
                .filter(s -> s.getHospital() != null && hospitalId.equals(s.getHospital().getId()))
                .map(mapper::toFhir)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        }
        throw new ResourceNotFoundException(id);
    }

    @Search
    public List<DocumentReference> search(
        @OptionalParam(name = "patient") ReferenceParam patient,
        @OptionalParam(name = "subject") ReferenceParam subject
    ) {
        UUID patientId = FhirIds.fromReference(patient != null ? patient : subject);
        if (patientId == null) return List.of();
        UUID hospitalId = FhirTenancy.requireHospitalScope("DocumentReference");

        List<DocumentReference> out = new ArrayList<>();

        // Uploaded documents have no hospital column: the whole section is
        // gated on the patient being registered at the caller's hospital,
        // and a foreign patient collapses to an empty list, never an error.
        if (patientVisibleAt(patientId, hospitalId)) {
            uploadedDocumentRepository
                .findByPatient_IdAndDeletedAtIsNullOrderByCreatedAtDesc(
                    patientId, PageRequest.of(0, MAX_PER_PATIENT))
                .forEach(d -> {
                    DocumentReference mapped = mapper.toFhir(d);
                    if (mapped != null) out.add(mapped);
                });
        }

        dischargeSummaryRepository
            .findWithAssociationsByPatient_IdAndHospital_IdOrderByDischargeDateDesc(patientId, hospitalId)
            .stream()
            .limit(MAX_PER_PATIENT)
            .forEach(s -> {
                DocumentReference mapped = mapper.toFhir(s);
                if (mapped != null) out.add(mapped);
            });
        return out;
    }

    private boolean patientVisibleAt(UUID patientId, UUID hospitalId) {
        return patientId != null
            && registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId).isPresent();
    }
}
