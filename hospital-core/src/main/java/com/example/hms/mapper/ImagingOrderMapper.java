package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.imaging.ImagingOrderDuplicateMatchDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.persistence.JpaProxyUtils;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class ImagingOrderMapper {

    /** Owning-entity label used in {@link JpaProxyUtils#safeInit} log lines. */
    private static final String OWNER = "ImagingOrder";

    /**
     * QA D4140 review: who placed an order written before V465 (no flag) is read from the roles, so the
     * portal shows a nurse's unsigned order of that time as waiting, as the server treats it. Optional:
     * the mappers built by hand in tests have none, and then only the flag counts.
     */
    private org.springframework.beans.factory.ObjectProvider<com.example.hms.utility.RoleValidator> roleValidator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRoleValidator(
            org.springframework.beans.factory.ObjectProvider<com.example.hms.utility.RoleValidator> roleValidator) {
        this.roleValidator = roleValidator;
    }

    /** The statuses in which an order nobody signed can still wait for a physician (before its report). */
    private static final java.util.Set<com.example.hms.enums.ImagingOrderStatus> BEFORE_THE_RESULT =
        java.util.EnumSet.of(com.example.hms.enums.ImagingOrderStatus.DRAFT,
            com.example.hms.enums.ImagingOrderStatus.ORDERED,
            com.example.hms.enums.ImagingOrderStatus.PENDING_AUTHORIZATION,
            com.example.hms.enums.ImagingOrderStatus.SCHEDULED,
            com.example.hms.enums.ImagingOrderStatus.IN_PROGRESS,
            // Review round 2: an older order done before anyone signed it still needs a physician before
            // its report is written.
            com.example.hms.enums.ImagingOrderStatus.COMPLETED);

    /**
     * QA D4140: the request waits for a physician. A flagged request waits while it is a draft; an order
     * written before the flag existed waits when nobody signed it and a nurse or a midwife of its hospital
     * placed it (the server's own reading, {@code ImagingOrderAuthorisation#awaitsPhysician}).
     */
    boolean awaitingPhysician(ImagingOrder order) {
        if (order.getProviderSignedAt() != null) {
            return false;
        }
        if (Boolean.TRUE.equals(order.getPhysicianAuthorisationRequired())) {
            return order.getStatus() == com.example.hms.enums.ImagingOrderStatus.DRAFT;
        }
        if (order.getPhysicianAuthorisationRequired() != null || !BEFORE_THE_RESULT.contains(order.getStatus())) {
            return false;
        }
        com.example.hms.utility.RoleValidator roles = roleValidator == null ? null : roleValidator.getIfAvailable();
        return roles != null && new com.example.hms.service.imaging.ImagingOrderAuthorisation(roles).awaitsPhysician(order);
    }

    public ImagingOrder toEntity(ImagingOrderRequestDTO dto, Patient patient, Hospital hospital) {
        if (dto == null) {
            return null;
        }

        return ImagingOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .modality(dto.getModality())
            .studyType(dto.getStudyType())
            .bodyRegion(dto.getBodyRegion())
            .laterality(dto.getLaterality())
            .priority(dto.getPriority())
            .status(Boolean.TRUE.equals(dto.getSaveAsDraft()) ? com.example.hms.enums.ImagingOrderStatus.DRAFT : com.example.hms.enums.ImagingOrderStatus.ORDERED)
            .clinicalQuestion(dto.getClinicalQuestion())
            .contrastRequired(dto.getContrastRequired())
            .contrastType(dto.getContrastType())
            .hasContrastAllergy(dto.getHasContrastAllergy())
            .contrastAllergyDetails(dto.getContrastAllergyDetails())
            .sedationRequired(dto.getSedationRequired())
            .sedationType(dto.getSedationType())
            .sedationNotes(dto.getSedationNotes())
            .requiresNpo(dto.getRequiresNpo())
            .hasImplantedDevice(dto.getHasImplantedDevice())
            .implantedDeviceDetails(dto.getImplantedDeviceDetails())
            .requiresPregnancyTest(dto.getRequiresPregnancyTest())
            .needsInterpreter(dto.getNeedsInterpreter())
            .additionalProtocols(dto.getAdditionalProtocols())
            .specialInstructions(dto.getSpecialInstructions())
            .scheduledDate(dto.getScheduledDate())
            .scheduledTime(dto.getScheduledTime())
            .appointmentLocation(dto.getAppointmentLocation())
            .portableStudy(dto.getPortableStudy())
            .requiresAuthorization(dto.getRequiresAuthorization())
            .authorizationNumber(dto.getAuthorizationNumber())
            .orderingProviderName(dto.getOrderingProviderName())
            .orderingProviderNpi(dto.getOrderingProviderNpi())
            .workflowNotes(dto.getWorkflowNotes())
            .encounterId(dto.getEncounterId())
            .build();
    }

    /**
     * An edit of the order. The fields of the portal's edit form (modality,
     * study, region, laterality, priority, clinical question) are the form's
     * to replace, so a blank one clears. QA D1822 class (r51): every other
     * field - the safety flags, the schedule, the appointment, the
     * pre-authorisation, the workflow notes, the visit - is kept when the
     * request leaves it out; an edit erased them all.
     */
    public void updateEntityFromRequest(ImagingOrder order, ImagingOrderRequestDTO dto) {
        if (order == null || dto == null) {
            return;
        }
        order.setModality(dto.getModality());
        order.setStudyType(dto.getStudyType());
        order.setBodyRegion(dto.getBodyRegion());
        order.setLaterality(dto.getLaterality());
        order.setPriority(dto.getPriority());
        order.setClinicalQuestion(dto.getClinicalQuestion());
        if (dto.getContrastRequired() != null) {
            order.setContrastRequired(dto.getContrastRequired());
        }
        if (dto.getContrastType() != null) {
            order.setContrastType(dto.getContrastType());
        }
        if (dto.getHasContrastAllergy() != null) {
            order.setHasContrastAllergy(dto.getHasContrastAllergy());
        }
        if (dto.getContrastAllergyDetails() != null) {
            order.setContrastAllergyDetails(dto.getContrastAllergyDetails());
        }
        if (dto.getSedationRequired() != null) {
            order.setSedationRequired(dto.getSedationRequired());
        }
        if (dto.getSedationType() != null) {
            order.setSedationType(dto.getSedationType());
        }
        if (dto.getSedationNotes() != null) {
            order.setSedationNotes(dto.getSedationNotes());
        }
        if (dto.getRequiresNpo() != null) {
            order.setRequiresNpo(dto.getRequiresNpo());
        }
        if (dto.getHasImplantedDevice() != null) {
            order.setHasImplantedDevice(dto.getHasImplantedDevice());
        }
        if (dto.getImplantedDeviceDetails() != null) {
            order.setImplantedDeviceDetails(dto.getImplantedDeviceDetails());
        }
        if (dto.getRequiresPregnancyTest() != null) {
            order.setRequiresPregnancyTest(dto.getRequiresPregnancyTest());
        }
        if (dto.getNeedsInterpreter() != null) {
            order.setNeedsInterpreter(dto.getNeedsInterpreter());
        }
        if (dto.getAdditionalProtocols() != null) {
            order.setAdditionalProtocols(dto.getAdditionalProtocols());
        }
        if (dto.getSpecialInstructions() != null) {
            order.setSpecialInstructions(dto.getSpecialInstructions());
        }
        if (dto.getScheduledDate() != null) {
            order.setScheduledDate(dto.getScheduledDate());
        }
        if (dto.getScheduledTime() != null) {
            order.setScheduledTime(dto.getScheduledTime());
        }
        if (dto.getAppointmentLocation() != null) {
            order.setAppointmentLocation(dto.getAppointmentLocation());
        }
        if (dto.getPortableStudy() != null) {
            order.setPortableStudy(dto.getPortableStudy());
        }
        if (dto.getRequiresAuthorization() != null) {
            order.setRequiresAuthorization(dto.getRequiresAuthorization());
        }
        if (dto.getAuthorizationNumber() != null) {
            order.setAuthorizationNumber(dto.getAuthorizationNumber());
        }
        if (dto.getWorkflowNotes() != null) {
            order.setWorkflowNotes(dto.getWorkflowNotes());
        }
        if (dto.getEncounterId() != null) {
            order.setEncounterId(dto.getEncounterId());
        }
    }

    public ImagingOrderResponseDTO toResponseDTO(ImagingOrder order) {
        return toResponseDTO(order, Collections.emptyList());
    }

    public ImagingOrderResponseDTO toResponseDTO(ImagingOrder order, List<ImagingOrder> duplicateMatches) {
        if (order == null) {
            return null;
        }

        // Force-initialise each lazy association up front and substitute null
        // when the referenced row was hard-deleted (dangling FK). Without this
        // defence the bare `order.getPatient().getMrnForHospital(...)` call
        // below blows up the whole list response with a 500 the moment a
        // single referenced patient row is missing — the exact failure
        // observed on dev's GET /api/imaging/orders endpoint.
        UUID orderId = order.getId();
        Patient patient = JpaProxyUtils.safeInit(order.getPatient(), OWNER, orderId, "patient");
        Hospital hospital = JpaProxyUtils.safeInit(order.getHospital(), OWNER, orderId, "hospital");

        return ImagingOrderResponseDTO.builder()
            .id(orderId)
            .authorUserId(order.getOrderingProviderUserId())
            .patientId(patient != null ? patient.getId() : null)
            .patientDisplayName(resolvePatientDisplayName(patient))
            .patientMrn(resolvePatientMrn(patient, hospital, orderId))
            .hospitalId(hospital != null ? hospital.getId() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .modality(order.getModality())
            .studyType(order.getStudyType())
            .catalogueItemId(order.getCatalogueItemId())
            .catalogueCode(order.getCatalogueCode())
            .bodyRegion(order.getBodyRegion())
            .laterality(order.getLaterality())
            .priority(order.getPriority())
            .status(order.getStatus())
            .clinicalQuestion(order.getClinicalQuestion())
            .contrastRequired(order.getContrastRequired())
            .contrastType(order.getContrastType())
            .hasContrastAllergy(order.getHasContrastAllergy())
            .contrastAllergyDetails(order.getContrastAllergyDetails())
            .sedationRequired(order.getSedationRequired())
            .sedationType(order.getSedationType())
            .sedationNotes(order.getSedationNotes())
            .requiresNpo(order.getRequiresNpo())
            .hasImplantedDevice(order.getHasImplantedDevice())
            .implantedDeviceDetails(order.getImplantedDeviceDetails())
            .requiresPregnancyTest(order.getRequiresPregnancyTest())
            .needsInterpreter(order.getNeedsInterpreter())
            .additionalProtocols(order.getAdditionalProtocols())
            .specialInstructions(order.getSpecialInstructions())
            .scheduledDate(order.getScheduledDate())
            .scheduledTime(order.getScheduledTime())
            .appointmentLocation(order.getAppointmentLocation())
            .portableStudy(order.getPortableStudy())
            .requiresAuthorization(order.getRequiresAuthorization())
            .authorizationNumber(order.getAuthorizationNumber())
            .orderedAt(order.getOrderedAt())
            .orderingProviderName(order.getOrderingProviderName())
            .orderingProviderNpi(order.getOrderingProviderNpi())
            .orderingProviderUserId(order.getOrderingProviderUserId())
            .providerSignedAt(order.getProviderSignedAt())
            .requestedByUserId(order.getRequestedByUserId())
            .requestedByName(order.getRequestedByName())
            .physicianAuthorisationRequired(order.getPhysicianAuthorisationRequired())
            .awaitingPhysicianAuthorisation(awaitingPhysician(order))
            .providerSignatureStatement(order.getProviderSignatureStatement())
            .encounterId(order.getEncounterId())
            .duplicateOfRecentOrder(order.getDuplicateOfRecentOrder())
            .duplicateReferenceOrderId(order.getDuplicateReferenceOrderId())
            .duplicateMatches(toDuplicateMatchDTOs(duplicateMatches))
            .duplicateOverride(order.getDuplicateOverride())
            .duplicateOverrideReason(order.getDuplicateOverrideReason())
            .duplicateOverrideNote(order.getDuplicateOverrideNote())
            .duplicateOverrideByName(order.getDuplicateOverrideByName())
            .duplicateOverrideAt(order.getDuplicateOverrideAt())
            .authorizationPending(order.getAuthorizationPendingSince() != null)
            .authorizationPendingSince(order.getAuthorizationPendingSince())
            .cancelledAt(order.getCancelledAt())
            .completedAt(order.getCompletedAt())
            .checkedInAt(order.getCheckedInAt())
            .checkedInByName(order.getCheckedInByName())
            .examStartedAt(order.getExamStartedAt())
            .performedByUserId(order.getPerformedByUserId())
            .performedByName(order.getPerformedByName())
            .examTechnicalNote(order.getExamTechnicalNote())
            .examContrastAdministered(order.getExamContrastAdministered())
            .examContrastDetails(order.getExamContrastDetails())
            .examRadiationDoseMgy(order.getExamRadiationDoseMgy())
            .examRecordedAt(order.getExamRecordedAt())
            .technicianMayPerform(order.getModality() != com.example.hms.enums.ImagingModality.ULTRASOUND
                || (hospital != null && hospital.isUltrasoundByNonPhysician()))
            .cancellationReason(order.getCancellationReason())
            .cancelledByName(order.getCancelledByName())
            .workflowNotes(order.getWorkflowNotes())
            .requiresFollowUpCall(order.getRequiresFollowUpCall())
            .updatedAt(order.getUpdatedAt())
            .build();
    }

    private List<ImagingOrderDuplicateMatchDTO> toDuplicateMatchDTOs(List<ImagingOrder> duplicates) {
        if (duplicates == null || duplicates.isEmpty()) {
            return List.of();
        }
        return duplicates.stream()
            .map(this::toDuplicateMatchDTO)
            .toList();
    }

    public ImagingOrderDuplicateMatchDTO toDuplicateMatchDTO(ImagingOrder order) {
        if (order == null) {
            return null;
        }
        return ImagingOrderDuplicateMatchDTO.builder()
            .orderId(order.getId())
            .modality(order.getModality())
            .bodyRegion(order.getBodyRegion())
            .studyType(order.getStudyType())
            .priority(order.getPriority())
            .status(order.getStatus())
            .orderedAt(order.getOrderedAt())
            .scheduledAt(resolveScheduledAt(order))
            .build();
    }

    private LocalDateTime resolveScheduledAt(ImagingOrder order) {
        if (order.getScheduledDate() == null) {
            return null;
        }
        if (order.getScheduledTime() == null || order.getScheduledTime().isBlank()) {
            return order.getScheduledDate().atStartOfDay();
        }
        try {
            LocalTime time = LocalTime.parse(order.getScheduledTime());
            return LocalDateTime.of(order.getScheduledDate(), time);
        } catch (DateTimeParseException ex) {
            return order.getScheduledDate().atStartOfDay();
        }
    }

    private String resolvePatientDisplayName(Patient patient) {
        // r44: the app's one name format, "First Middle Last" (QA D1036), not
        // "Last, First": the imaging list read "Traoré, Seydou" where every
        // other screen reads "Seydou Traoré".
        return patient == null ? null : patient.getFullName();
    }

    /**
     * Resolves the patient's MRN for the order's hospital. Both the patient
     * and hospital arguments are expected to have already been passed through
     * {@link JpaProxyUtils#safeInit} so the top-level FK is known to be
     * valid. The second-order guard (try/catch) covers the lazy
     * {@code patient.hospitalRegistrations} collection: a registration row
     * whose hospital_id points at a hard-deleted Hospital would otherwise
     * throw on {@code reg.getHospital().getId()} inside
     * {@link Patient#getMrnForHospital(UUID)}. Mirrors the proven pattern in
     * {@code ConsultationServiceImpl.toResponseDTO}.
     */
    private String resolvePatientMrn(Patient patient, Hospital hospital, UUID orderId) {
        if (patient == null || hospital == null || hospital.getId() == null) {
            return null;
        }
        try {
            return patient.getMrnForHospital(hospital.getId());
        } catch (EntityNotFoundException | JpaObjectRetrievalFailureException ex) {
            log.warn("⚠️ ImagingOrder({}) patient {} has dangling hospitalRegistration FK; MRN unavailable.",
                orderId, patient.getId());
            return null;
        }
    }
}
