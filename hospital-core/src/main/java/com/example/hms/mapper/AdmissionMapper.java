package com.example.hms.mapper;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.AdmissionOrderSet;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.AdmissionOrderSetResponseDTO;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.persistence.JpaProxyUtils;
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

    /** Owning-entity label used in {@link JpaProxyUtils#safeInit} log lines. */
    private static final String OWNER = "Admission";

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
        UUID admissionId = admission.getId();
        dto.setId(admissionId);

        // Force-initialise each lazy association up front and substitute null
        // when the referenced row was hard-deleted (dangling FK). Without this
        // defence the bare `admission.getPatient().getFirstName()` blows up
        // the whole list response with a 500 the moment a single referenced
        // row is missing — visible on dev's /super-admin/recent-activity.
        Patient patient = JpaProxyUtils.safeInit(admission.getPatient(), OWNER, admissionId, "patient");
        Hospital hospital = JpaProxyUtils.safeInit(admission.getHospital(), OWNER, admissionId, "hospital");
        Staff admittingProvider = JpaProxyUtils.safeInit(
            admission.getAdmittingProvider(), OWNER, admissionId, "admittingProvider");
        Department department = JpaProxyUtils.safeInit(
            admission.getDepartment(), OWNER, admissionId, "department");

        // Patient info
        if (patient != null) {
            dto.setPatientId(patient.getId());
            dto.setPatientName(buildFullName(patient.getFirstName(), patient.getLastName()));
            // resolvePatientMrn touches patient.getHospitalRegistrations() so
            // we must only invoke it once safeInit has confirmed the patient
            // row still exists; otherwise the lazy registration collection
            // would re-throw EntityNotFoundException.
            dto.setPatientMrn(resolvePatientMrn(admission, hospital));
        }

        // Hospital info
        if (hospital != null) {
            dto.setHospitalId(hospital.getId());
            dto.setHospitalName(hospital.getName());
        }

        // Admitting provider
        if (admittingProvider != null) {
            dto.setAdmittingProviderId(admittingProvider.getId());
            dto.setAdmittingProviderName(admittingProvider.getFullName());
        }

        // Resolve the patient's most-recent non-terminal encounter at this hospital.
        // Single query per admission — same shape as the registration lookup above.
        // Returns null when no open encounter exists, which the picker handles
        // gracefully (medication + lab fan-out tolerate null encounter).
        dto.setCurrentEncounterId(resolveCurrentEncounterId(patient, hospital));

        // Department
        if (department != null) {
            dto.setDepartmentId(department.getId());
            dto.setDepartmentName(department.getName());
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
        Staff attendingPhysician = JpaProxyUtils.safeInit(
            admission.getAttendingPhysician(), OWNER, admissionId, "attendingPhysician");
        if (attendingPhysician != null) {
            dto.setAttendingPhysicianId(attendingPhysician.getId());
            dto.setAttendingPhysicianName(attendingPhysician.getFullName());
        }

        dto.setConsultingPhysicians(admission.getConsultingPhysicians());

        // Discharge info
        dto.setDischargeDisposition(admission.getDischargeDisposition());
        dto.setDischargeSummary(admission.getDischargeSummary());
        dto.setDischargeInstructions(admission.getDischargeInstructions());

        Staff dischargingProvider = JpaProxyUtils.safeInit(
            admission.getDischargingProvider(), OWNER, admissionId, "dischargingProvider");
        if (dischargingProvider != null) {
            dto.setDischargingProviderId(dischargingProvider.getId());
            dto.setDischargingProviderName(dischargingProvider.getFullName());
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

        private UUID resolveCurrentEncounterId(Patient patient, Hospital hospital) {
            if (patient == null || patient.getId() == null
                || hospital == null || hospital.getId() == null) {
                return null;
            }
            List<Encounter> open = encounterRepository
                .findByPatient_IdAndHospital_IdAndStatusNotIn(
                    patient.getId(),
                    hospital.getId(),
                    TERMINAL_ENCOUNTER_STATUSES);
            return open.stream()
                .filter(Objects::nonNull)
                .filter(e -> e.getEncounterDate() != null)
                .max(Comparator.comparing(Encounter::getEncounterDate))
                .map(Encounter::getId)
                .orElse(null);
        }

        private String resolvePatientMrn(Admission admission, Hospital hospital) {
            if (admission == null || admission.getPatient() == null ||
                admission.getPatient().getHospitalRegistrations() == null ||
                admission.getPatient().getHospitalRegistrations().isEmpty()) {
                return null;
            }

            UUID hospitalId = hospital != null ? hospital.getId() : null;

            return admission.getPatient().getHospitalRegistrations().stream()
                .filter(Objects::nonNull)
                .filter(reg -> hospitalId == null || (reg.getHospital() != null && hospitalId.equals(reg.getHospital().getId())))
                .map(PatientHospitalRegistration::getMrn)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        }

        private static String buildFullName(String first, String last) {
            String f = first == null ? "" : first.trim();
            String l = last == null ? "" : last.trim();
            String full = (f + " " + l).trim();
            return full.isEmpty() ? null : full;
        }
}
