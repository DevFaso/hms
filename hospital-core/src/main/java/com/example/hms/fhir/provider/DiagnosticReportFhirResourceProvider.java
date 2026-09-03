package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.DiagnosticReportFhirMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.MicroCultureResult;
import com.example.hms.model.MicroIsolate;
import com.example.hms.model.MicroSusceptibility;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.MicroCultureResultRepository;
import com.example.hms.repository.MicroIsolateRepository;
import com.example.hms.repository.MicroSusceptibilityRepository;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.IdType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
// Read-only TX (retrofit of the item-42 providers): open-in-view=false, so
// the mappers' lazy walks after the repository call were a latent
// LazyInitializationException on every read - same fix as the item-43 pair.
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class DiagnosticReportFhirResourceProvider implements IResourceProvider {

    private static final int MAX_PER_PATIENT = 200;
    private static final String LAB_PREFIX = "laborder-";
    private static final String MICRO_PREFIX = "micro-";
    private static final String IMAGING_PREFIX = "imgreport-";

    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final MicroCultureResultRepository microCultureRepository;
    private final MicroIsolateRepository microIsolateRepository;
    private final MicroSusceptibilityRepository microSusceptibilityRepository;
    private final ImagingReportRepository imagingReportRepository;
    private final DiagnosticReportFhirMapper mapper;

    public DiagnosticReportFhirResourceProvider(
        LabOrderRepository labOrderRepository,
        LabResultRepository labResultRepository,
        MicroCultureResultRepository microCultureRepository,
        MicroIsolateRepository microIsolateRepository,
        MicroSusceptibilityRepository microSusceptibilityRepository,
        ImagingReportRepository imagingReportRepository,
        DiagnosticReportFhirMapper mapper
    ) {
        this.labOrderRepository = labOrderRepository;
        this.labResultRepository = labResultRepository;
        this.microCultureRepository = microCultureRepository;
        this.microIsolateRepository = microIsolateRepository;
        this.microSusceptibilityRepository = microSusceptibilityRepository;
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
                .map(this::mapCulture)
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
        // open order would bury the real ones. Results for the whole page
        // are fetched in ONE query and grouped, not one query per order.
        List<LabOrder> orders = labOrderRepository
            .findByPatient_IdAndHospital_IdOrderByOrderDatetimeDesc(
                patientId, hospitalId, PageRequest.of(0, MAX_PER_PATIENT))
            .getContent();
        Map<UUID, List<LabResult>> resultsByOrder = orders.isEmpty()
            ? Map.of()
            : labResultRepository.findByLabOrder_IdIn(orders.stream().map(LabOrder::getId).toList())
                .stream()
                .filter(r -> r.getLabOrder() != null && r.getLabOrder().getId() != null)
                .collect(Collectors.groupingBy(r -> r.getLabOrder().getId()));
        for (LabOrder order : orders) {
            List<LabResult> results = resultsByOrder.getOrDefault(order.getId(), List.of());
            if (results.isEmpty()) continue;
            DiagnosticReport mapped = mapper.toFhir(order, results);
            if (mapped != null) out.add(mapped);
        }

        // Micro: cultures for the page, isolates for all of them in one
        // query, susceptibilities for all isolates in one more.
        List<MicroCultureResult> cultures = microCultureRepository
            .findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(
                patientId, hospitalId, PageRequest.of(0, MAX_PER_PATIENT))
            .getContent();
        Map<UUID, List<MicroIsolate>> isolatesByCulture = cultures.isEmpty()
            ? Map.of()
            : microIsolateRepository.findByCultureResult_IdInOrderByIsolateNumberAscCreatedAtAsc(
                    cultures.stream().map(MicroCultureResult::getId).toList())
                .stream()
                .filter(i -> i.getCultureResult() != null && i.getCultureResult().getId() != null)
                .collect(Collectors.groupingBy(i -> i.getCultureResult().getId()));
        List<UUID> isolateIds = isolatesByCulture.values().stream()
            .flatMap(List::stream).map(MicroIsolate::getId).toList();
        List<MicroSusceptibility> susceptibilities = isolateIds.isEmpty()
            ? List.of()
            : microSusceptibilityRepository.findByIsolate_IdInOrderByAntibioticNameAsc(isolateIds);
        for (MicroCultureResult culture : cultures) {
            DiagnosticReport mapped = mapper.toFhir(culture,
                isolatesByCulture.getOrDefault(culture.getId(), List.of()), susceptibilities);
            if (mapped != null) out.add(mapped);
        }

        // Imaging: paged over latest REPORT versions directly — a cap
        // applied to candidate orders would let a run of resultless recent
        // orders push older valid reports out of the window entirely.
        imagingReportRepository
            .findByImagingOrder_Patient_IdAndHospital_IdAndLatestVersionIsTrueOrderByPerformedAtDesc(
                patientId, hospitalId, PageRequest.of(0, MAX_PER_PATIENT))
            .forEach(r -> {
                DiagnosticReport mapped = mapper.toFhir(r);
                if (mapped != null) out.add(mapped);
            });
        return out;
    }

    private DiagnosticReport mapCulture(MicroCultureResult culture) {
        List<MicroIsolate> isolates = microIsolateRepository
            .findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc(culture.getId());
        List<MicroSusceptibility> susceptibilities = isolates.isEmpty()
            ? List.of()
            : microSusceptibilityRepository.findByIsolate_IdInOrderByAntibioticNameAsc(
                isolates.stream().map(MicroIsolate::getId).toList());
        return mapper.toFhir(culture, isolates, susceptibilities);
    }

    private static UUID requireHospitalScope() {
        return FhirTenancy.requireHospitalScope("DiagnosticReport");
    }
}
