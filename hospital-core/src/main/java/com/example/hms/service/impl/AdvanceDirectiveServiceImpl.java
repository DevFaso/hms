package com.example.hms.service.impl;

import java.util.Set;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.enums.AdvanceDirectiveStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdvanceDirectiveMapper;
import com.example.hms.model.AdvanceDirective;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AdvanceDirectiveRequestDTO;
import com.example.hms.payload.dto.AdvanceDirectiveResponseDTO;
import com.example.hms.repository.AdvanceDirectiveRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.AdvanceDirectiveService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdvanceDirectiveServiceImpl implements AdvanceDirectiveService {

    private final AdvanceDirectiveRepository directiveRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final AdvanceDirectiveMapper mapper;
    private final RoleValidator roleValidator;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;

    @Override
    @Transactional(readOnly = true)
    public List<AdvanceDirectiveResponseDTO> listForPatient(UUID patientId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId == null) {
            // Super-admin global view: every directive, no reach to account.
            return directiveRepository.findByPatient_Id(patientId).stream().map(mapper::toResponseDto).toList();
        }
        // E9 #59 — a directive is a property of the patient (a DNR made at
        // Hôpital A binds at Hôpital B); it follows them across the readable
        // hospitals and every foreign row surfaced is accounted.
        UUID requesterUserId = roleValidator.getCurrentUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, activeHospitalId);
        List<AdvanceDirectiveResponseDTO> directives = directiveRepository
            .findByPatient_IdAndHospital_IdIn(patientId, readable).stream()
            .map(mapper::toResponseDto)
            .toList();
        reachRecorder.recordReach(patientId, activeHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(directives, AdvanceDirectiveResponseDTO::getHospitalId, activeHospitalId),
            "Cross-hospital advance directive read on the treatment relationship");
        return directives;
    }

    @Override
    @Transactional
    public AdvanceDirectiveResponseDTO create(UUID patientId, AdvanceDirectiveRequestDTO request) {
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));

        Hospital hospital = resolveHospital(request.getHospitalId());

        // The hospital is pinned to the caller above, but until 2026-08-21 the
        // PATIENT was a bare findById — the one unguarded write path in this
        // file, in a class whose own resolveHospital comment warns about
        // exactly this vector. A DNR recorded against another hospital's
        // patient is a clinical statement made by someone with no relationship
        // to them; 404 rather than 403, matching loadScoped, so a foreign
        // patient id is indistinguishable from a nonexistent one.
        if (!patient.isRegisteredInHospital(hospital.getId())) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }

        AdvanceDirective directive = new AdvanceDirective();
        directive.setPatient(patient);
        directive.setHospital(hospital);
        // A newly recorded directive is in force unless the caller says otherwise.
        directive.setStatus(request.getStatus() != null ? request.getStatus() : AdvanceDirectiveStatus.ACTIVE);
        applyEditableFields(directive, request);
        validateDates(directive);

        return mapper.toResponseDto(directiveRepository.save(directive));
    }

    @Override
    @Transactional
    public AdvanceDirectiveResponseDTO update(UUID id, AdvanceDirectiveRequestDTO request) {
        AdvanceDirective directive = loadScoped(id);
        if (request.getStatus() != null) {
            directive.setStatus(request.getStatus());
        }
        applyEditableFields(directive, request);
        validateDates(directive);
        return mapper.toResponseDto(directiveRepository.save(directive));
    }

    @Override
    @Transactional
    public AdvanceDirectiveResponseDTO revoke(UUID id) {
        AdvanceDirective directive = loadScoped(id);
        directive.setStatus(AdvanceDirectiveStatus.REVOKED);
        directive.setLastReviewedAt(LocalDateTime.now());
        return mapper.toResponseDto(directiveRepository.save(directive));
    }

    /** Cross-hospital rows read as absent, matching the house convention. */
    private AdvanceDirective loadScoped(UUID id) {
        AdvanceDirective directive = directiveRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Advance directive not found with ID: " + id));
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null
            && (directive.getHospital() == null
                || !activeHospitalId.equals(directive.getHospital().getId()))) {
            throw new ResourceNotFoundException("Advance directive not found with ID: " + id);
        }
        return directive;
    }

    private Hospital resolveHospital(UUID requestedHospitalId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        // A scoped caller may only record against their own hospital; the
        // client-supplied id would otherwise be a cross-tenant write vector.
        UUID targetId = activeHospitalId != null ? activeHospitalId : requestedHospitalId;
        if (targetId == null) {
            throw new BusinessException("A hospital is required to record an advance directive.");
        }
        return hospitalRepository.findById(targetId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", targetId));
    }

    private void applyEditableFields(AdvanceDirective directive, AdvanceDirectiveRequestDTO request) {
        directive.setDirectiveType(request.getDirectiveType());
        directive.setDescription(request.getDescription());
        directive.setEffectiveDate(request.getEffectiveDate());
        directive.setExpirationDate(request.getExpirationDate());
        directive.setWitnessName(request.getWitnessName());
        directive.setPhysicianName(request.getPhysicianName());
        directive.setDocumentLocation(request.getDocumentLocation());
        directive.setSourceSystem(request.getSourceSystem());
    }

    /**
     * A directive that expires before it takes effect never applies to anybody,
     * and storing one guarantees a later reader draws the wrong conclusion about
     * which directive was in force.
     */
    private void validateDates(AdvanceDirective directive) {
        if (directive.getEffectiveDate() != null && directive.getExpirationDate() != null
            && directive.getExpirationDate().isBefore(directive.getEffectiveDate())) {
            throw new BusinessException("An advance directive cannot expire before it takes effect.");
        }
    }
}
