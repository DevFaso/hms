package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.DiagnosticReportFhirMapper;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.MicroCultureResultRepository;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FHIR R4 {@code DiagnosticReport} provider (Tier 2 item 42), sourced from
 * the three report domains:
 * <ul>
 *   <li>{@code laborder-{uuid}} — a lab order with its results, each result
 *       referenced as the Observation provider's {@code labresult-{uuid}};</li>
 *   <li>{@code micro-{uuid}} — a microbiology culture (#472);</li>
 *   <li>{@code imgreport-{uuid}} — an imaging read (#26), latest versions
 *       only on search so a corrected report does not appear beside the
 *       version it corrected.</li>
 * </ul>
 *
 * <p>Read-only: reports are authored and signed through the ceremonies
 * (#26's sign path, micro finalization). Tenant contract identical to the
 * Observation provider — scope required, foreign rows collapse to
 * not-found.
 */
@Component
public class DiagnosticReportFhirResourceProvider implements IResourceProvider {

    private static final int MAX_PER_PATIENT = 200;
    private static final String LAB_PREFIX = "laborder-";
    private static final String MICRO_PREFIX = "micro-";
    private static final String IMAGING_PREFIX = "imgreport-";

    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final MicroCultureResultRepository microCultureRepository;
    private final ImagingOrderRepository imagingOrderRepository;
    private final ImagingReportRepository imagingReportRepository;
    private final DiagnosticReportFhirMapper mapper;

    public DiagnosticReportFhirResourceProvider(
        LabOrderRepository labOrderRepository,
        LabResultRepository labResultRepository,
        MicroCultureResultRepository microCultureRepository,
        ImagingOrderRepository imagingOrderRepository,
        ImagingReportRepository imagingReportRepository,
        DiagnosticReportFhirMapper mapper
    ) {
        this.labOrderRepository = labOrderRepository;
        this.labResultRepository = labResultRepository;
        this.microCultureRepository = microCultureRepository;
        this.imagingOrderRepository = imagingOrderRepository;
        this.imagingReportRepository = imagingReportRepository;
        this.mapper = mapper;
    }

    @Override
    public Class<DiagnosticReport> getResourceType() {
        return DiagnosticReport.class;
    }

    @Read
    public DiagnosticReport read(@IdParam IdType id) {
        if (id == null || id.getIdPart() == null) {
            throw new ResourceNotFoundException(id);
        }
        UUID hospitalId = requireHospitalScope();
        String idPart = id.getIdPart();
        if (idPart.startsWith(LAB_PREFIX)) {
            UUID uuid = FhirIds.tryParse(idPart.substring(LAB_PREFIX.length()));
            if (uuid == null) throw new ResourceNotFoundException(id);
            return labOrderRepository.findById(uuid)
                .filter(o -> o.getHospital() != null && hospitalId.equals(o.getHospital().getId()))
                .map(o -> mapper.toFhir(o, labResultRepository.findByLabOrder_Id(o.getId())))
                .orElseThrow(() -> new ResourceNotFoundException(id));
        }
        if (idPart.startsWith(MICRO_PREFIX)) {
            UUID uuid = FhirIds.tryParse(idPart.substring(MICRO_PREFIX.length()));
            if (uuid == null) throw new ResourceNotFoundException(id);
            return microCultureRepository.findById(uuid)
                .filter(c -> c.getHospital() != null && hospitalId.equals(c.getHospital().getId()))
                .map(mapper::toFhir)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        }
        if (idPart.startsWith(IMAGING_PREFIX)) {
            UUID uuid = FhirIds.tryParse(idPart.substring(IMAGING_PREFIX.length()));
            if (uuid == null) throw new ResourceNotFoundException(id);
            return imagingReportRepository.findById(uuid)
                .filter(r -> r.getHospital() != null && hospitalId.equals(r.getHospital().getId()))
                .map(mapper::toFhir)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        }
        throw new ResourceNotFoundException(id);
    }

    @Search
    public List<DiagnosticReport> search(
        @OptionalParam(name = "patient") ReferenceParam patient,
        @OptionalParam(name = "subject") ReferenceParam subject
    ) {
        UUID patientId = FhirIds.fromReference(patient != null ? patient : subject);
        if (patientId == null) return List.of();
        UUID hospitalId = requireHospitalScope();

        List<DiagnosticReport> out = new ArrayList<>();

        // Lab: one report per order that has at least one result row. An
        // order with nothing reportable yet is a ServiceRequest, not a
        // DiagnosticReport — emitting empty "registered" reports for every
        // open order would bury the real ones.
        for (LabOrder order : labOrderRepository
            .findByPatient_IdAndHospital_IdOrderByOrderDatetimeDesc(
                patientId, hospitalId, PageRequest.of(0, MAX_PER_PATIENT))) {
            var results = labResultRepository.findByLabOrder_Id(order.getId());
            if (results.isEmpty()) continue;
            DiagnosticReport mapped = mapper.toFhir(order, results);
            if (mapped != null) out.add(mapped);
        }

        microCultureRepository.findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(
                patientId, hospitalId, PageRequest.of(0, MAX_PER_PATIENT))
            .forEach(c -> {
                DiagnosticReport mapped = mapper.toFhir(c);
                if (mapped != null) out.add(mapped);
            });

        // Imaging: via the patient's orders, latest report versions only.
        List<UUID> orderIds = imagingOrderRepository
            .findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(
                patientId, hospitalId, PageRequest.of(0, MAX_PER_PATIENT))
            .map(ImagingOrder::getId)
            .toList();
        if (!orderIds.isEmpty()) {
            imagingReportRepository.findByImagingOrder_IdInAndLatestVersionIsTrue(orderIds)
                .forEach(r -> {
                    DiagnosticReport mapped = mapper.toFhir(r);
                    if (mapped != null) out.add(mapped);
                });
        }
        return out;
    }

    private static UUID requireHospitalScope() {
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        if (hospitalId == null) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.FORBIDDEN)
                .setDiagnostics("FHIR DiagnosticReport reads require an active hospital scope; "
                    + "supply X-Hospital-Id or authenticate as a hospital-scoped user.");
            throw new ForbiddenOperationException("An active hospital scope is required.", outcome);
        }
        return hospitalId;
    }
}
