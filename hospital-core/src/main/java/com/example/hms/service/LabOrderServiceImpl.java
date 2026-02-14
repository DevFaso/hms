package com.example.hms.service;

import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.DiagnosisCodeValidator;
import com.example.hms.utility.RoleValidator;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LabOrderServiceImpl implements LabOrderService {

    private final LabOrderRepository labOrderRepository;
    private final PatientRepository patientRepository;
    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final LabOrderMapper labOrderMapper;
    private final RoleValidator roleValidator;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    @Override
    @Transactional
    public LabOrderResponseDTO createLabOrder(LabOrderRequestDTO request, Locale locale) {
        LabOrder newLabOrder = buildLabOrder(null, request, true);
        return labOrderMapper.toLabOrderResponseDTO(labOrderRepository.save(newLabOrder));
    }

    @Override
    @Transactional
    public LabOrderResponseDTO updateLabOrder(UUID id, LabOrderRequestDTO request, Locale locale) {
        LabOrder existing = labOrderRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("laborder.notfound"));

        LabOrder updated = buildLabOrder(existing, request, false);
        return labOrderMapper.toLabOrderResponseDTO(labOrderRepository.save(updated));
    }

    private LabOrder buildLabOrder(LabOrder base, LabOrderRequestDTO request, boolean isNew) {
    String clinicalIndication = normalizeRequiredText(request.getClinicalIndication(), "Clinical indication is required for lab orders.");
    String medicalNecessityNote = normalizeRequiredText(request.getMedicalNecessityNote(), "Medical necessity rationale is required for lab orders.");
        String notes = normalizeOptionalText(request.getNotes());

        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));

        Staff staff = staffRepository.findById(request.getOrderingStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("staff.notfound"));

        UUID requestedHospitalId = request.getHospitalId();
        Encounter encounter = null;
        if (request.getEncounterId() != null) {
            encounter = encounterRepository.findById(request.getEncounterId())
                .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound"));
        }

        Hospital hospital = encounter != null ? encounter.getHospital() : null;
        if (hospital != null && requestedHospitalId != null && !hospital.getId().equals(requestedHospitalId)) {
            throw new BusinessException("Encounter hospital does not match the requested hospital.");
        }

        if (hospital == null && requestedHospitalId != null) {
            hospital = hospitalRepository.findById(requestedHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));
        }

        if (hospital == null) {
            throw new BusinessException("Lab orders must reference a hospital either via encounter or hospital identifier.");
        }

        boolean patientRegistered = patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(
            patient.getId(),
            hospital.getId()
        );
        if (!patientRegistered) {
            throw new BusinessException("Patient is not registered with the specified hospital.");
        }

        // Role check based on assignment, not JWT
        UUID userId = staff.getUser().getId();
        UUID hospitalId = hospital.getId();
        boolean authorized = roleValidator.canOrderLabTests(userId, hospitalId);
        if (!authorized) {
            log.warn("User {} is not authorized to place lab orders in hospital {}", userId, hospitalId);
            throw new BusinessException("Only doctors, physicians, or nurse practitioners can place lab orders.");
        }

        LabTestDefinition testDefinition = labTestDefinitionRepository.findById(request.getLabTestDefinitionId())
            .orElseThrow(() -> new ResourceNotFoundException("labtestdefinition.notfound"));

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(request.getAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));

        if (isNew && labOrderRepository.existsByPatient_IdAndLabTestDefinition_IdAndOrderDatetime(
            patient.getId(), testDefinition.getId(), request.getOrderDatetime())) {
            throw new BusinessException("Duplicate lab order detected for the same test, patient, and date.");
        }

        LabOrder labOrder = (base != null) ? base : new LabOrder();

        labOrder.setPatient(patient);
        labOrder.setOrderingStaff(staff);
        labOrder.setEncounter(encounter);
        labOrder.setHospital(hospital);
        labOrder.setLabTestDefinition(testDefinition);
        labOrder.setAssignment(assignment);
        labOrder.setOrderDatetime(request.getOrderDatetime());
        labOrder.setStatus(LabOrderStatus.valueOf(request.getStatus().toUpperCase()));
        labOrder.setClinicalIndication(clinicalIndication);
        labOrder.setMedicalNecessityNote(medicalNecessityNote);
        labOrder.setNotes(notes);
        labOrder.setPrimaryDiagnosisCode(resolvePrimaryDiagnosisCode(request, base));
        labOrder.setAdditionalDiagnosisCodes(new ArrayList<>(resolveAdditionalDiagnosisCodes(request, base)));
        LabOrderChannel orderChannel = resolveOrderChannel(request.getOrderChannel(), base);
        labOrder.setOrderChannel(orderChannel);
        labOrder.setOrderChannelOther(resolveOrderChannelOther(orderChannel, request, base));
    boolean sharedDocumentation = resolveDocumentationSharedFlag(orderChannel, request, base);
    enforceDocumentationCompliance(orderChannel, sharedDocumentation);
    labOrder.setDocumentationSharedWithLab(sharedDocumentation);
        labOrder.setDocumentationReference(resolveDocumentationReference(sharedDocumentation, request, base));
        labOrder.setOrderingProviderNpi(resolveOrderingProviderNpi(staff, request.getOrderingProviderNpi(), base));
        labOrder.setProviderSignatureDigest(resolveProviderSignatureDigest(request.getProviderSignature(), base, isNew));
        labOrder.setSignedAt(resolveSignedAt(request.getSignedAt(), base));
        labOrder.setSignedByUserId(staff.getUser().getId());
        applyStandingOrderMetadata(labOrder, request, base, labOrder.getOrderDatetime());

        return labOrder;
    }

    @Override
    @Transactional(readOnly = true)
    public LabOrderResponseDTO getLabOrderById(UUID id, Locale locale) {
        return labOrderRepository.findById(id)
            .map(labOrderMapper::toLabOrderResponseDTO)
            .orElseThrow(() -> new ResourceNotFoundException("laborder.notfound"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getAllLabOrders(Locale locale) {
        return labOrderRepository.findAll().stream()
            .map(labOrderMapper::toLabOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public void deleteLabOrder(UUID id, Locale locale) {
        if (!labOrderRepository.existsById(id)) {
            throw new ResourceNotFoundException("laborder.notfound");
        }
        labOrderRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LabOrderResponseDTO> searchLabOrders(UUID patientId, LocalDateTime fromDate, LocalDateTime toDate, Pageable pageable, Locale locale) {
        return labOrderRepository.search(patientId, fromDate, toDate, pageable)
            .map(labOrderMapper::toLabOrderResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getLabOrdersByPatientId(UUID patientId, Locale locale) {
        return labOrderRepository.findByPatient_Id(patientId).stream()
            .map(labOrderMapper::toLabOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getLabOrdersByStaffId(UUID staffId, Locale locale) {
        return labOrderRepository.findByOrderingStaff_Id(staffId).stream()
            .map(labOrderMapper::toLabOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getLabOrdersByLabTestDefinitionId(UUID labTestDefinitionId, Locale locale) {
        return labOrderRepository.findByLabTestDefinition_Id(labTestDefinitionId).stream()
            .map(labOrderMapper::toLabOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getLabOrdersByStatus(LabOrderStatus status, Locale locale) {
        return labOrderRepository.findByStatus(status).stream()
            .map(labOrderMapper::toLabOrderResponseDTO)
            .toList();
    }

    private String normalizeRequiredText(String value, String errorMessage) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            throw new BusinessException(errorMessage);
        }
        return normalized;
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolvePrimaryDiagnosisCode(LabOrderRequestDTO request, LabOrder base) {
        String normalized = normalizeOptionalText(request.getPrimaryDiagnosisCode());
        if (normalized == null) {
            if (base != null) {
                return base.getPrimaryDiagnosisCode();
            }
            throw new BusinessException("Primary diagnosis code is required for lab orders.");
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!DiagnosisCodeValidator.isValidIcd10(normalized)) {
            throw new BusinessException("Invalid ICD-10 diagnosis code: " + normalized);
        }
        return normalized;
    }

    private List<String> resolveAdditionalDiagnosisCodes(LabOrderRequestDTO request, LabOrder base) {
        List<String> requested = DiagnosisCodeValidator.normalizeList(request.getAdditionalDiagnosisCodes());
        if (requested.isEmpty()) {
            if (request.getAdditionalDiagnosisCodes() == null && base != null && base.getAdditionalDiagnosisCodes() != null) {
                return new ArrayList<>(base.getAdditionalDiagnosisCodes());
            }
            return List.of();
        }
        for (String code : requested) {
            if (!DiagnosisCodeValidator.isValidIcd10(code)) {
                throw new BusinessException("Invalid ICD-10 diagnosis code: " + code);
            }
        }
        return requested;
    }

    private LabOrderChannel resolveOrderChannel(String requestedChannel, LabOrder base) {
        if (requestedChannel != null && !requestedChannel.isBlank()) {
            try {
                return LabOrderChannel.fromCode(requestedChannel);
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ex.getMessage());
            }
        }
        if (base != null && base.getOrderChannel() != null) {
            return base.getOrderChannel();
        }
        return LabOrderChannel.ELECTRONIC;
    }

    private String resolveOrderChannelOther(LabOrderChannel channel, LabOrderRequestDTO request, LabOrder base) {
        if (channel != LabOrderChannel.OTHER) {
            return null;
        }
        String provided = normalizeOptionalText(request.getOrderChannelOther());
        if (provided != null) {
            return provided;
        }
        if (base != null && base.getOrderChannelOther() != null) {
            return base.getOrderChannelOther();
        }
        throw new BusinessException("orderChannelOther must be provided when orderChannel is OTHER.");
    }

    private String resolveOrderingProviderNpi(Staff staff, String override, LabOrder base) {
        String staffNpi = normalizeOptionalText(staff.getNpi());
        String requestNpi = normalizeOptionalText(override);
        String existing = base != null ? base.getOrderingProviderNpi() : null;
        String resolved;
        if (staffNpi != null) {
            resolved = staffNpi;
        } else if (requestNpi != null) {
            resolved = requestNpi;
        } else {
            resolved = existing;
        }
        if (resolved == null) {
            throw new BusinessException("Ordering providers must have an NPI on record or provided in the request.");
        }
        if (!resolved.matches("\\d{10}")) {
            throw new BusinessException("NPI must be a 10-digit numeric identifier.");
        }
        if (staffNpi != null && requestNpi != null && !staffNpi.equals(requestNpi)) {
            throw new BusinessException("Provided NPI does not match the staff member's recorded NPI.");
        }
        return resolved;
    }

    private String resolveProviderSignatureDigest(String signaturePayload, LabOrder base, boolean isNew) {
        String normalized = normalizeOptionalText(signaturePayload);
        if (normalized == null) {
            if (base != null && base.getProviderSignatureDigest() != null) {
                return base.getProviderSignatureDigest();
            }
            if (isNew) {
                throw new BusinessException("An electronic signature attestation is required for lab orders.");
            }
            return null;
        }
        return computeSignatureDigest(normalized);
    }

    private LocalDateTime resolveSignedAt(LocalDateTime requestedSignedAt, LabOrder base) {
        if (requestedSignedAt != null) {
            return requestedSignedAt;
        }
        if (base != null && base.getSignedAt() != null) {
            return base.getSignedAt();
        }
        return LocalDateTime.now();
    }

    private void applyStandingOrderMetadata(LabOrder labOrder, LabOrderRequestDTO request, LabOrder base, LocalDateTime orderDatetime) {
        boolean standingOrder = resolveStandingOrderFlag(request, base);
        labOrder.setStandingOrder(standingOrder);
        if (!standingOrder) {
            labOrder.setStandingOrderExpiresAt(null);
            labOrder.setStandingOrderLastReviewedAt(null);
            labOrder.setStandingOrderReviewDueAt(null);
            labOrder.setStandingOrderReviewIntervalDays(null);
            labOrder.setStandingOrderReviewNotes(null);
            return;
        }
        LocalDateTime expiresAt = resolveStandingOrderExpiresAt(request, base, orderDatetime);
        LocalDateTime lastReviewedAt = resolveStandingOrderLastReviewedAt(request, base);
        int reviewIntervalDays = resolveStandingOrderReviewInterval(request, base);
        LocalDateTime reviewDueAt = computeStandingOrderReviewDueAt(lastReviewedAt, reviewIntervalDays);

        labOrder.setStandingOrderExpiresAt(expiresAt);
        labOrder.setStandingOrderLastReviewedAt(lastReviewedAt);
        labOrder.setStandingOrderReviewIntervalDays(reviewIntervalDays);
        labOrder.setStandingOrderReviewDueAt(reviewDueAt);
        labOrder.setStandingOrderReviewNotes(resolveStandingOrderReviewNotes(request, base));
    }

    private boolean resolveDocumentationSharedFlag(LabOrderChannel channel, LabOrderRequestDTO request, LabOrder base) {
        Boolean requested = request.getDocumentationSharedWithLab();
        if (requested != null) {
            return requested;
        }
        if (base != null) {
            return base.isDocumentationSharedWithLab();
        }
        return channel == LabOrderChannel.PORTAL || channel == LabOrderChannel.ELECTRONIC;
    }

    private void enforceDocumentationCompliance(LabOrderChannel channel, boolean documentationShared) {
        if (!documentationShared) {
            String channelName = channel != null ? channel.name().toLowerCase(Locale.ENGLISH) : "the selected";
            throw new BusinessException("Medicare guidelines require lab orders submitted via " + channelName + " channel to include documentation shared with the performing laboratory.");
        }
    }

    private String resolveDocumentationReference(boolean shared, LabOrderRequestDTO request, LabOrder base) {
        if (!shared) {
            return null;
        }
        String requestedReference = normalizeOptionalText(request.getDocumentationReference());
        if (requestedReference != null) {
            return requestedReference;
        }
        if (base != null && base.getDocumentationReference() != null) {
            return base.getDocumentationReference();
        }
        throw new BusinessException("Documentation reference is required when sharing paperwork with the lab.");
    }

    private boolean resolveStandingOrderFlag(LabOrderRequestDTO request, LabOrder base) {
        Boolean requestedFlag = request.getStandingOrder();
        if (requestedFlag != null) {
            return requestedFlag;
        }
        if (base != null) {
            return base.isStandingOrder();
        }
        return false;
    }

    private LocalDateTime resolveStandingOrderExpiresAt(LabOrderRequestDTO request, LabOrder base, LocalDateTime orderDatetime) {
        LocalDateTime expiresAt = request.getStandingOrderExpiresAt();
        if (expiresAt == null && base != null) {
            expiresAt = base.getStandingOrderExpiresAt();
        }
        if (expiresAt == null) {
            throw new BusinessException("Standing orders must include an expiration timestamp.");
        }
        if (orderDatetime != null && expiresAt.isBefore(orderDatetime)) {
            throw new BusinessException("Standing order expiration must be after the order date.");
        }
        return expiresAt;
    }

    private LocalDateTime resolveStandingOrderLastReviewedAt(LabOrderRequestDTO request, LabOrder base) {
        LocalDateTime lastReviewedAt = request.getStandingOrderLastReviewedAt();
        if (lastReviewedAt == null && base != null) {
            lastReviewedAt = base.getStandingOrderLastReviewedAt();
        }
        if (lastReviewedAt == null) {
            throw new BusinessException("Standing orders must include the last review timestamp.");
        }
        return lastReviewedAt;
    }

    private int resolveStandingOrderReviewInterval(LabOrderRequestDTO request, LabOrder base) {
        Integer reviewIntervalDays = request.getStandingOrderReviewIntervalDays();
        if ((reviewIntervalDays == null || reviewIntervalDays <= 0) && base != null && base.getStandingOrderReviewIntervalDays() != null) {
            reviewIntervalDays = base.getStandingOrderReviewIntervalDays();
        }
        if (reviewIntervalDays == null || reviewIntervalDays <= 0) {
            throw new BusinessException("Standing orders require a positive review interval (days).");
        }
        return reviewIntervalDays;
    }

    private LocalDateTime computeStandingOrderReviewDueAt(LocalDateTime lastReviewedAt, int reviewIntervalDays) {
        LocalDateTime reviewDueAt = lastReviewedAt.plusDays(reviewIntervalDays);
        if (reviewDueAt.isBefore(LocalDateTime.now())) {
            throw new BusinessException("Standing order review is overdue. Please review before placing new orders.");
        }
        return reviewDueAt;
    }

    private String resolveStandingOrderReviewNotes(LabOrderRequestDTO request, LabOrder base) {
        String rawNotes = request.getStandingOrderReviewNotes();
        String reviewNotes = normalizeOptionalText(rawNotes);
        if (reviewNotes != null) {
            return reviewNotes;
        }
        if (rawNotes != null) {
            return null;
        }
        return base != null ? base.getStandingOrderReviewNotes() : null;
    }

    private String computeSignatureDigest(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HEX_FORMAT.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
