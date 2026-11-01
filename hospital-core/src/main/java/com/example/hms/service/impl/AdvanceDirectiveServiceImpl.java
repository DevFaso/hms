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
            CrossHospitalReachRecorder.reachOf(directives.stream().map(AdvanceDirectiveResponseDTO::getHospitalId).toList(), activeHospitalId),
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
        // QA D1148 (round 21, user decision): a directive is never recorded
        // from defaults. The clinician states its status; it used to default
        // to ACTIVE, so one click recorded an active DNR.
        com.example.hms.service.support.ClinicalContent.requirePresent(request.getStatus(), "status",
            "advanceDirective.status.required");
        directive.setStatus(request.getStatus());
        applyEditableFields(directive, request);
        validateDates(directive);
        requireOrderDetails(directive);

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
        requireOrderDetails(directive);
        return mapper.toResponseDto(directiveRepository.save(directive));
    }

    /**
     * QA D1148: an active resuscitation order (DNR, POLST) names the physician
     * who gave it and the date it takes effect, as a paper order does; the
     * banner showed an active DNR with neither.
     */
    static void requireOrderDetails(AdvanceDirective directive) {
        boolean resuscitationOrder = directive.getDirectiveType() == com.example.hms.enums.AdvanceDirectiveType.DO_NOT_RESUSCITATE
            || directive.getDirectiveType()
                == com.example.hms.enums.AdvanceDirectiveType.PHYSICIAN_ORDERS_FOR_LIFE_SUSTAINING_TREATMENT;
        if (!resuscitationOrder || directive.getStatus() != AdvanceDirectiveStatus.ACTIVE) {
            return;
        }
        com.example.hms.service.support.ClinicalContent.requirePresent(directive.getPhysicianName(),
            "physicianName", "advanceDirective.physician.required");
        com.example.hms.service.support.ClinicalContent.requirePresent(directive.getEffectiveDate(),
            "effectiveDate", "advanceDirective.effectiveDate.required");
    }

    @Override
    @Transactional
    public AdvanceDirectiveResponseDTO revoke(UUID id, String reason) {
        AdvanceDirective directive = loadScoped(id);
        // QA D1186 class (round 22): a revoked directive stays revoked; a second
        // revoke overwrote the reason it was revoked for.
        if (directive.getStatus() == AdvanceDirectiveStatus.REVOKED) {
            throw new BusinessException("advanceDirective.revoke.alreadyRevoked");
        }
        // QA D1148 (round 21): a revoked directive says why, on the server.
        directive.setRevocationReason(com.example.hms.service.support.CancelledOrders.requireCancelReason(reason));
        directive.setStatus(AdvanceDirectiveStatus.REVOKED);
        directive.setLastReviewedAt(LocalDateTime.now());
        return mapper.toResponseDto(directiveRepository.save(directive));
    }

    /** Cross-hospital rows read as absent, matching the house convention. */
    private AdvanceDirective loadScoped(UUID id) {
        AdvanceDirective directive = directiveRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("advanceDirective.notFound", id));
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null
            && (directive.getHospital() == null
                || !activeHospitalId.equals(directive.getHospital().getId()))) {
            throw new ResourceNotFoundException("advanceDirective.notFound", id);
        }
        return directive;
    }

    private Hospital resolveHospital(UUID requestedHospitalId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        // A scoped caller may only record against their own hospital; the
        // client-supplied id would otherwise be a cross-tenant write vector.
        UUID targetId = activeHospitalId != null ? activeHospitalId : requestedHospitalId;
        if (targetId == null) {
            throw new BusinessException("advanceDirective.hospital.required");
        }
        return hospitalRepository.findClinicalById(targetId)
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
            throw new BusinessException("advanceDirective.expiryBeforeEffective");
        }
    }
}
