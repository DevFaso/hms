package com.example.hms.service.signature;

import com.example.hms.enums.SignatureType;
import com.example.hms.model.Hospital;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Finds the hospital that owns the record a signature is placed on (QA D71).
 *
 * <p>A signature over a record that does not exist, or that lives in another
 * hospital, attests to nothing. Types without a backing record store
 * ({@code OPERATIVE_NOTE}, {@code PATHOLOGY_REPORT}, {@code CARE_PLAN},
 * {@code GENERIC_REPORT}) resolve to empty: there is nothing to verify the
 * id against, so they cannot be signed through the generic endpoint.
 */
@Component
@RequiredArgsConstructor
public class SignableReportLocator {

    private final DischargeSummaryRepository dischargeSummaryRepository;
    private final LabResultRepository labResultRepository;
    private final ImagingReportRepository imagingReportRepository;
    private final ConsultationRepository consultationRepository;
    private final EncounterNoteRepository encounterNoteRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final ProcedureOrderRepository procedureOrderRepository;

    /** The owning hospital id, or empty when the record does not exist or the type has no store. */
    @Transactional(readOnly = true)
    public Optional<UUID> owningHospitalId(SignatureType type, UUID reportId) {
        if (type == null || reportId == null) {
            return Optional.empty();
        }
        Optional<Hospital> hospital = switch (type) {
            case DISCHARGE_SUMMARY -> dischargeSummaryRepository.findById(reportId).map(r -> r.getHospital());
            case LAB_RESULT -> labResultRepository.findById(reportId)
                .map(r -> r.getLabOrder() != null ? r.getLabOrder().getHospital() : null);
            case IMAGING_REPORT -> imagingReportRepository.findById(reportId).map(r -> r.getHospital());
            case CONSULTATION_NOTE -> consultationRepository.findById(reportId).map(r -> r.getHospital());
            case PROGRESS_NOTE, ED_NOTE -> encounterNoteRepository.findById(reportId).map(r -> r.getHospital());
            case MEDICATION_ORDER -> prescriptionRepository.findById(reportId).map(r -> r.getHospital());
            case PROCEDURE_REPORT -> procedureOrderRepository.findById(reportId).map(r -> r.getHospital());
            case OPERATIVE_NOTE, PATHOLOGY_REPORT, CARE_PLAN, GENERIC_REPORT -> Optional.empty();
        };
        return hospital.map(Hospital::getId);
    }
}
