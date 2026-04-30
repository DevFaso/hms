package com.example.hms.mapper;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.AdmissionOrderSet;
import com.example.hms.model.Encounter;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.AdmissionOrderSetResponseDTO;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.repository.EncounterRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Mapper for Admission entity and DTOs
 */
@Component
public class AdmissionMapper {

    /** Encounter statuses that are terminal — excluded from the active lookup. */
    private static final Set<EncounterStatus> TERMINAL_ENCOUNTER_STATUSES =
        EnumSet.of(EncounterStatus.COMPLETED, EncounterStatus.CANCELLED);

    private final EncounterRepository encounterRepository;

    public AdmissionMapper(EncounterRepository encounterRepository) {
        this.encounterRepository = encounterRepository;
    }

    /**
     * Convert Admission entity to response DTO
     */
    public AdmissionResponseDTO toResponseDTO(Admission admission) {
        if (admission == null) {
            return null;
        }

        AdmissionResponseDTO dto = new AdmissionResponseDTO();
        dto.setId(admission.getId());

        // Patient info
        if (admission.getPatient() != null) {
            dto.setPatientId(admission.getPatient().getId());
            dto.setPatientName(admission.getPatient().getFirstName() + " " + admission.getPatient().getLastName());
                dto.setPatientMrn(resolvePatientMrn(admission));
        }

        // Hospital info
        if (admission.getHospital() != null) {
            dto.setHospitalId(admission.getHospital().getId());
            dto.setHospitalName(admission.getHospital().getName());
        }

        // Admitting provider
        if (admission.getAdmittingProvider() != null) {
            dto.setAdmittingProviderId(admission.getAdmittingProvider().getId());
                dto.setAdmittingProviderName(admission.getAdmittingProvider().getFullName());
        }

        // Resolve the patient's most-recent non-terminal encounter at this hospital.
        // Single query per admission — same shape as the registration lookup above.
        // Returns null when no open encounter exists, which the picker handles
        // gracefully (medication + lab fan-out tolerate null encounter).
        dto.setCurrentEncounterId(resolveCurrentEncounterId(admission));

        // Department
        if (admission.getDepartment() != null) {
            dto.setDepartmentId(admission.getDepartment().getId());
            dto.setDepartmentName(admission.getDepartment().getName());
        }

        dto.setRoomBed(admission.getRoomBed());
        dto.setAdmissionType(admission.getAdmissionType());
        dto.setStatus(admission.getStatus());
        dto.setAcuityLevel(admission.getAcuityLevel());

        dto.setAdmissionDateTime(admission.getAdmissionDateTime());
        dto.setExpectedDischargeDateTime(admission.getExpectedDischargeDateTime());
        dto.setActualDischargeDateTime(admission.getActualDischargeDateTime());

        dto.setChiefComplaint(admission.getChiefComplaint());
        dto.setPrimaryDiagnosisCode(admission.getPrimaryDiagnosisCode());
        dto.setPrimaryDiagnosisDescription(admission.getPrimaryDiagnosisDescription());
        dto.setSecondaryDiagnoses(admission.getSecondaryDiagnoses());
        dto.setAdmissionSource(admission.getAdmissionSource());

        // Convert order sets
        if (admission.getAppliedOrderSets() != null && !admission.getAppliedOrderSets().isEmpty()) {
            dto.setAppliedOrderSets(
                admission.getAppliedOrderSets().stream()
                    .map(this::toOrderSetResponseDTO)
                    .toList()
            );
        }

        dto.setCustomOrders(admission.getCustomOrders());
        dto.setAdmissionNotes(admission.getAdmissionNotes());

        // Attending physician
        if (admission.getAttendingPhysician() != null) {
            dto.setAttendingPhysicianId(admission.getAttendingPhysician().getId());
                dto.setAttendingPhysicianName(admission.getAttendingPhysician().getFullName());
        }

        dto.setConsultingPhysicians(admission.getConsultingPhysicians());

        // Discharge info
        dto.setDischargeDisposition(admission.getDischargeDisposition());
        dto.setDischargeSummary(admission.getDischargeSummary());
        dto.setDischargeInstructions(admission.getDischargeInstructions());

        if (admission.getDischargingProvider() != null) {
            dto.setDischargingProviderId(admission.getDischargingProvider().getId());
                dto.setDischargingProviderName(admission.getDischargingProvider().getFullName());
        }

        dto.setFollowUpAppointments(admission.getFollowUpAppointments());
        dto.setInsuranceAuthNumber(admission.getInsuranceAuthNumber());
        dto.setLengthOfStayDays(admission.getLengthOfStayDays());
        dto.setMetadata(admission.getMetadata());

        dto.setCreatedAt(admission.getCreatedAt());
        dto.setUpdatedAt(admission.getUpdatedAt());

        return dto;
    }

    /**
     * Convert AdmissionOrderSet to response DTO
     */
    public AdmissionOrderSetResponseDTO toOrderSetResponseDTO(AdmissionOrderSet orderSet) {
        if (orderSet == null) {
            return null;
        }

        AdmissionOrderSetResponseDTO dto = new AdmissionOrderSetResponseDTO();
        dto.setId(orderSet.getId());
        dto.setName(orderSet.getName());
        dto.setDescription(orderSet.getDescription());
        dto.setAdmissionType(orderSet.getAdmissionType());

        if (orderSet.getDepartment() != null) {
            dto.setDepartmentId(orderSet.getDepartment().getId());
            dto.setDepartmentName(orderSet.getDepartment().getName());
        }

        if (orderSet.getHospital() != null) {
            dto.setHospitalId(orderSet.getHospital().getId());
            dto.setHospitalName(orderSet.getHospital().getName());
        }

        dto.setOrderItems(orderSet.getOrderItems());
        dto.setClinicalGuidelines(orderSet.getClinicalGuidelines());
        dto.setActive(orderSet.getActive());
        dto.setVersion(orderSet.getVersion());

        if (orderSet.getCreatedBy() != null) {
            dto.setCreatedById(orderSet.getCreatedBy().getId());
                dto.setCreatedByName(orderSet.getCreatedBy().getFullName());
        }

        if (orderSet.getLastModifiedBy() != null) {
            dto.setLastModifiedById(orderSet.getLastModifiedBy().getId());
                dto.setLastModifiedByName(orderSet.getLastModifiedBy().getFullName());
        }

        dto.setCreatedAt(orderSet.getCreatedAt());
        dto.setUpdatedAt(orderSet.getUpdatedAt());
        dto.setDeactivatedAt(orderSet.getDeactivatedAt());
        dto.setDeactivationReason(orderSet.getDeactivationReason());
        dto.setOrderCount(orderSet.getOrderCount());

        return dto;
    }

        private UUID resolveCurrentEncounterId(Admission admission) {
            if (admission == null || admission.getPatient() == null
                || admission.getPatient().getId() == null
                || admission.getHospital() == null
                || admission.getHospital().getId() == null) {
                return null;
            }
            List<Encounter> open = encounterRepository
                .findByPatient_IdAndHospital_IdAndStatusNotIn(
                    admission.getPatient().getId(),
                    admission.getHospital().getId(),
                    TERMINAL_ENCOUNTER_STATUSES);
            return open.stream()
                .filter(Objects::nonNull)
                .filter(e -> e.getEncounterDate() != null)
                .max(Comparator.comparing(Encounter::getEncounterDate))
                .map(Encounter::getId)
                .orElse(null);
        }

        private String resolvePatientMrn(Admission admission) {
            if (admission == null || admission.getPatient() == null ||
                admission.getPatient().getHospitalRegistrations() == null ||
                admission.getPatient().getHospitalRegistrations().isEmpty()) {
                return null;
            }

            UUID hospitalId = admission.getHospital() != null ? admission.getHospital().getId() : null;

            return admission.getPatient().getHospitalRegistrations().stream()
                .filter(Objects::nonNull)
                .filter(reg -> hospitalId == null || (reg.getHospital() != null && hospitalId.equals(reg.getHospital().getId())))
                .map(PatientHospitalRegistration::getMrn)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        }
}
