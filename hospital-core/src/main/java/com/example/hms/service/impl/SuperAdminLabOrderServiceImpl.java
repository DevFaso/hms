package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminLabOrderCreateRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.LabOrderService;
import com.example.hms.service.SuperAdminLabOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SuperAdminLabOrderServiceImpl implements SuperAdminLabOrderService {

    private final OrganizationRepository organizationRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final StaffRepository staffRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final LabOrderService labOrderService;

    @Override
    public LabOrderResponseDTO createLabOrder(SuperAdminLabOrderCreateRequestDTO request, Locale locale) {
        String organizationKey = requireValue(request.getOrganizationIdentifier(), "organizationIdentifier");
        String hospitalKey = requireValue(request.getHospitalIdentifier(), "hospitalIdentifier");
        String patientKey = requireValue(request.getPatientIdentifier(), "patientIdentifier");
        String staffKey = requireValue(request.getOrderingStaffIdentifier(), "orderingStaffIdentifier");
        String labTestKey = requireValue(request.getLabTestIdentifier(), "labTestIdentifier");
        String status = requireValue(request.getStatus(), "status").toUpperCase(Locale.ENGLISH);

        log.info("Super admin lab order request org={} hospital={} patient={} staff={} labTest={} status={}",
            organizationKey, hospitalKey, patientKey, staffKey, labTestKey, status);

        Organization organization = resolveOrganization(organizationKey);
        log.debug("Resolved organization {} (id={})", organization.getName(), organization.getId());
        Hospital hospital = resolveHospital(hospitalKey, organization);
        log.debug("Resolved hospital {} (id={}) for organization {}", hospital.getCode(), hospital.getId(), organization.getId());
        PatientHospitalRegistration registration = resolvePatientRegistration(hospital, patientKey);
        log.debug("Resolved patient registration {} for patient {}", registration.getId(), registration.getPatient().getId());
        Patient patient = registration.getPatient();
        Staff staff = resolveStaff(hospital, staffKey, request.getOrderingStaffRole());
        log.debug("Resolved staff {} (assignment={})", staff.getId(), staff.getAssignment() != null ? staff.getAssignment().getId() : null);
        LabTestDefinition labTestDefinition = resolveLabTestDefinition(hospital, labTestKey);
        log.info("Resolved lab test definition {} (hospital={}) for identifier {}",
            labTestDefinition.getId(),
            labTestDefinition.getHospital() != null ? labTestDefinition.getHospital().getId() : "GLOBAL",
            labTestKey);
        UserRoleHospitalAssignment assignment = staff.getAssignment();

        if (assignment == null || assignment.getHospital() == null) {
            throw new BusinessException("Ordering staff does not have a valid hospital assignment.");
        }

        if (!assignment.getHospital().getId().equals(hospital.getId())) {
            throw new BusinessException("Ordering staff assignment does not match the selected hospital.");
        }

        String requestedRole = normalize(request.getOrderingStaffRole());
        if (requestedRole != null && assignment.getRole() != null) {
            String assignmentRole = normalize(assignment.getRole().getCode());
            if (assignmentRole == null) {
                assignmentRole = normalize(assignment.getRole().getName());
            }
            if (assignmentRole == null || !assignmentRole.equalsIgnoreCase(requestedRole)) {
                throw new BusinessException("Ordering staff does not hold the requested role within this hospital.");
            }
        }

        LocalDateTime orderDatetime = request.getOrderDatetime() != null
            ? request.getOrderDatetime()
            : LocalDateTime.now();

        SuperAdminLabOrderContext context = new SuperAdminLabOrderContext(
            patient,
            hospital,
            staff,
            labTestDefinition,
            assignment,
            status,
            orderDatetime
        );

        LabOrderRequestDTO labOrderRequest = buildLabOrderRequest(request, context);

        return labOrderService.createLabOrder(labOrderRequest, locale);
    }

    private LabOrderRequestDTO buildLabOrderRequest(
        SuperAdminLabOrderCreateRequestDTO request,
        SuperAdminLabOrderContext context
    ) {
        String clinicalIndication = requireValue(request.getClinicalIndication(), "clinicalIndication");
        String medicalNecessity = requireValue(request.getMedicalNecessityNote(), "medicalNecessityNote");
        String primaryDiagnosis = requireValue(request.getPrimaryDiagnosisCode(), "primaryDiagnosisCode");
        List<String> additionalCodes = sanitizeCodes(request.getAdditionalDiagnosisCodes());
        String orderChannel = requireValue(request.getOrderChannel(), "orderChannel");
        Boolean documentationShared = requireBoolean(request.getDocumentationSharedWithLab(), "documentationSharedWithLab");
        String providerSignature = requireValue(request.getProviderSignature(), "providerSignature");
        LocalDateTime signedAt = request.getSignedAt() != null ? request.getSignedAt() : LocalDateTime.now();

        String documentationReference = normalize(request.getDocumentationReference());
        if (Boolean.TRUE.equals(documentationShared) && documentationReference == null) {
            throw new BusinessException("documentationReference is required when documentation is shared with the laboratory.");
        }

        return LabOrderRequestDTO.builder()
            .patientId(context.patient().getId())
            .hospitalId(context.hospital().getId())
            .orderingStaffId(context.staff().getId())
            .labTestDefinitionId(context.labTestDefinition().getId())
            .assignmentId(context.assignment().getId())
            .status(context.status())
            .priority(normalize(request.getPriority()))
            .notes(normalize(request.getNotes()))
            .orderDatetime(context.orderDatetime())
            .testName(context.labTestDefinition().getName())
            .testCode(context.labTestDefinition().getTestCode())
            .testResults(defaultList(request.getTestResults()))
            .clinicalIndication(clinicalIndication)
            .medicalNecessityNote(medicalNecessity)
            .primaryDiagnosisCode(primaryDiagnosis)
            .additionalDiagnosisCodes(additionalCodes)
            .orderChannel(orderChannel)
            .orderChannelOther(normalize(request.getOrderChannelOther()))
            .documentationSharedWithLab(documentationShared)
            .documentationReference(documentationReference)
            .orderingProviderNpi(normalize(request.getOrderingProviderNpi()))
            .providerSignature(providerSignature)
            .signedAt(signedAt)
            .standingOrder(request.getStandingOrder())
            .standingOrderExpiresAt(request.getStandingOrderExpiresAt())
            .standingOrderLastReviewedAt(request.getStandingOrderLastReviewedAt())
            .standingOrderReviewIntervalDays(request.getStandingOrderReviewIntervalDays())
            .standingOrderReviewNotes(normalize(request.getStandingOrderReviewNotes()))
            .build();
    }

    private record SuperAdminLabOrderContext(
        Patient patient,
        Hospital hospital,
        Staff staff,
        LabTestDefinition labTestDefinition,
        UserRoleHospitalAssignment assignment,
        String status,
        LocalDateTime orderDatetime
    ) {}

    private Boolean requireBoolean(Boolean value, String field) {
        if (value == null) {
            throw new BusinessException("Missing required field: " + field);
        }
        return value;
    }

    private List<String> sanitizeCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return codes.stream()
            .map(this::normalize)
            .filter(item -> item != null && !item.isBlank())
            .map(item -> item.toUpperCase(Locale.ENGLISH))
            .toList();
    }

    private Organization resolveOrganization(String identifier) {
        String normalized = identifier.trim();
        return organizationRepository.findByCode(normalized.toUpperCase(Locale.ENGLISH))
            .or(() -> organizationRepository.findByNameIgnoreCase(normalized))
            .orElseThrow(() -> new ResourceNotFoundException("organization.notfound"));
    }

    private Hospital resolveHospital(String identifier, Organization organization) {
        String normalized = identifier.trim();
        Hospital hospital = hospitalRepository.findByCodeIgnoreCase(normalized)
            .or(() -> hospitalRepository.findByNameIgnoreCase(normalized))
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));

        log.debug("resolveHospital matched hospital {} with organization {} (expected {})",
            hospital.getId(),
            hospital.getOrganization() != null ? hospital.getOrganization().getId() : null,
            organization.getId());

        if (hospital.getOrganization() == null || !hospital.getOrganization().getId().equals(organization.getId())) {
            throw new BusinessException("Hospital does not belong to the specified organization.");
        }
        return hospital;
    }

    private PatientHospitalRegistration resolvePatientRegistration(Hospital hospital, String identifier) {
        String normalized = identifier.trim();
        List<PatientHospitalRegistration> matches = registrationRepository.findActiveByHospitalIdAndIdentifier(
            hospital.getId(),
            normalized.toLowerCase(Locale.ENGLISH)
        );

        if (matches.isEmpty()) {
            throw new ResourceNotFoundException("patient.notfound");
        }

        if (matches.size() > 1) {
            throw new BusinessException("Multiple patients match the provided identifier within this hospital.");
        }

    return matches.get(0);
    }

    private Staff resolveStaff(Hospital hospital, String identifier, String roleCode) {
        String normalizedIdentifier = identifier.trim().toLowerCase(Locale.ENGLISH);
        String normalizedRole = roleCode != null ? roleCode.trim().toUpperCase(Locale.ENGLISH) : null;

        List<Staff> matches = staffRepository.findActiveByHospitalAndRoleAndIdentifier(
            hospital.getId(),
            normalizedRole,
            normalizedIdentifier
        );

        if (matches.isEmpty()) {
            throw new ResourceNotFoundException("staff.notfound");
        }

        if (matches.size() > 1) {
            throw new BusinessException("Multiple staff members match the provided identifier. Please refine the selection using the role or username.");
        }

    Staff staff = matches.get(0);
        if (!staff.getHospital().getId().equals(hospital.getId())) {
            throw new BusinessException("Staff member is not assigned to the specified hospital.");
        }
        return staff;
    }

    private LabTestDefinition resolveLabTestDefinition(Hospital hospital, String identifier) {
        String normalized = identifier.trim();
        log.debug("Resolving lab test definition identifier={} for hospital={} (org={})",
            normalized,
            hospital.getId(),
            hospital.getOrganization() != null ? hospital.getOrganization().getId() : null);
    Optional<LabTestDefinition> byId = tryResolveLabTestDefinitionById(normalized);
        if (byId.isPresent()) {
            log.info("Resolved lab test definition by id {} for hospital {}", normalized, hospital.getId());
            return byId.get();
        }

        return labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), normalized)
            .map(def -> logAndReturn(def, "Resolved hospital-scoped lab test {} for hospital {}", normalized, hospital.getId()))
            .or(() -> labTestDefinitionRepository.findActiveGlobalByIdentifier(normalized)
                .map(def -> logAndReturn(def, "Resolved global lab test {}", normalized)))
            .or(() -> selectFallbackDefinition(normalized, hospital))
            .orElseThrow(() -> new ResourceNotFoundException("labtestdefinition.notfound"));
    }

    private Optional<LabTestDefinition> tryResolveLabTestDefinitionById(String identifier) {
        try {
            UUID id = UUID.fromString(identifier);
            return labTestDefinitionRepository.findById(id)
                .filter(LabTestDefinition::isActive)
                .filter(this::isDefinitionApplicable);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private Optional<LabTestDefinition> selectFallbackDefinition(String identifier, Hospital hospital) {
        List<LabTestDefinition> allCandidates = labTestDefinitionRepository.findActiveByIdentifier(identifier);
        log.debug("Fallback resolution for identifier={} yielded {} active candidate(s)", identifier, allCandidates.size());

        if (allCandidates.isEmpty()) {
            log.info("No active lab test definitions found anywhere for identifier {}", identifier);
            return Optional.empty();
        }

        // Log all candidates for debugging
        allCandidates.forEach(def -> log.debug("Candidate: id={}, code={}, hospital={}",
            def.getId(), def.getTestCode(), hospitalIdOrGlobal(def)));

        List<LabTestDefinition> candidates = allCandidates.stream()
            .filter(def -> {
                boolean applicable = isDefinitionApplicable(def);
                String definitionHospitalId = hospitalIdOrGlobal(def);
                String definitionOrganizationId = organizationIdOrNone(def.getHospital());
                String targetOrganizationId = organizationIdOrNone(hospital);

                if (!applicable) {
                    log.info("Filtered out inactive lab test definition {} while resolving identifier={} (definition hospital={}, definitionOrg={}, targetOrg={})",
                        def.getId(), identifier, definitionHospitalId, definitionOrganizationId, targetOrganizationId);
                } else if (def.getHospital() != null && !def.getHospital().getId().equals(hospital.getId())) {
                    log.debug("Accepting nationally scoped lab test {} sourced from hospital {} (definitionOrg={}, targetOrg={})",
                        def.getId(), definitionHospitalId, definitionOrganizationId, targetOrganizationId);
                } else {
                    log.debug("Candidate lab test {} applicable for hospital {} (source={})",
                        def.getId(), hospital.getId(), definitionHospitalId);
                }
                return applicable;
            })
            .sorted((left, right) -> compareByHospitalAffinity(left, right, hospital))
            .toList();

        log.debug("Fallback candidates remaining for identifier={} count={}", identifier, candidates.size());

        if (candidates.isEmpty()) {
            log.info("No fallback lab test candidates resolved for identifier {}", identifier);
            return Optional.empty();
        }
        LabTestDefinition selected = candidates.get(0);
        log.info("Selected fallback lab test {} for hospital {} (candidate count={})",
            selected.getId(), hospital.getId(), candidates.size());
        return Optional.of(selected);
    }

    private boolean isDefinitionApplicable(LabTestDefinition definition) {
        return definition != null && definition.isActive();
    }

    private String hospitalIdOrGlobal(LabTestDefinition definition) {
        return definition.getHospital() == null ? "GLOBAL" : definition.getHospital().getId().toString();
    }

    private String organizationIdOrNone(Hospital hospital) {
        if (hospital == null || hospital.getOrganization() == null) {
            return "NONE";
        }
        return hospital.getOrganization().getId().toString();
    }

    private int compareByHospitalAffinity(LabTestDefinition left, LabTestDefinition right, Hospital targetHospital) {
        boolean leftMatchesHospital = left.getHospital() != null && left.getHospital().getId().equals(targetHospital.getId());
        boolean rightMatchesHospital = right.getHospital() != null && right.getHospital().getId().equals(targetHospital.getId());

        if (leftMatchesHospital && !rightMatchesHospital) {
            return -1;
        }
        if (!leftMatchesHospital && rightMatchesHospital) {
            return 1;
        }
        // Prefer global entries next, then most recently updated
        boolean leftGlobal = left.getHospital() == null;
        boolean rightGlobal = right.getHospital() == null;
        if (leftGlobal && !rightGlobal) {
            return -1;
        }
        if (!leftGlobal && rightGlobal) {
            return 1;
        }

        LocalDateTime leftUpdated = left.getUpdatedAt();
        LocalDateTime rightUpdated = right.getUpdatedAt();
        if (leftUpdated != null && rightUpdated != null) {
            return rightUpdated.compareTo(leftUpdated);
        }
        if (leftUpdated != null) {
            return -1;
        }
        if (rightUpdated != null) {
            return 1;
        }
        return left.getId().compareTo(right.getId());
    }

    private String requireValue(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new BusinessException("Missing required field: " + field);
        }
        return normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> defaultList(List<String> input) {
        return input == null ? List.of() : input;
    }

    private LabTestDefinition logAndReturn(LabTestDefinition definition, String messageTemplate, Object... args) {
        log.info(messageTemplate, args);
        return definition;
    }
}
