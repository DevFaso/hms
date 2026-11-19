package com.example.hms.service;

import com.example.hms.enums.LabQcEventLevel;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.LabQcTarget;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabQcTargetRequestDTO;
import com.example.hms.payload.dto.LabQcTargetResponseDTO;
import com.example.hms.repository.LabQcTargetRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * r63 D2304: the laboratory director's QC targets (mean and SD per test and
 * control level) at the hospital the caller acts for. Saving one is the
 * director's approval: it is stamped with who and when. A target is always a
 * hospital's own: a super-admin in global view picks a hospital first.
 */
@Service
@RequiredArgsConstructor
public class LabQcTargetService {

    private final LabQcTargetRepository targetRepository;
    private final LabTestDefinitionRepository definitionRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;

    @Transactional(readOnly = true)
    public List<LabQcTargetResponseDTO> list(UUID testDefinitionId) {
        UUID hospitalId = requirePinnedHospital();
        List<LabQcTarget> rows = testDefinitionId != null
            ? targetRepository.findByHospitalIdAndTestDefinition_Id(hospitalId, testDefinitionId)
            : targetRepository.findByHospitalId(hospitalId);
        return rows.stream().map(LabQcTargetService::toDto).toList();
    }

    @Transactional
    public LabQcTargetResponseDTO save(LabQcTargetRequestDTO request) {
        UUID hospitalId = requirePinnedHospital();
        LabQcEventLevel level;
        try {
            level = LabQcEventLevel.valueOf(request.getQcLevel().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new FieldBusinessException("qcLevel", "lab.qc.unknownLevel", String.valueOf(request.getQcLevel()));
        }
        if (request.getTargetMean() == null) {
            throw new FieldBusinessException("targetMean", "lab.qc.target.meanRequired");
        }
        if (request.getTargetSd() == null || request.getTargetSd().signum() <= 0) {
            throw new FieldBusinessException("targetSd", "lab.qc.target.sdPositive");
        }
        if (request.getTestDefinitionId() == null) {
            throw new FieldBusinessException("testDefinitionId", "lab.qc.target.testRequired");
        }
        LabTestDefinition definition = definitionRepository.findById(request.getTestDefinitionId())
            .orElseThrow(() -> new ResourceNotFoundException("labtestdef.notfound"));
        LabQcEventServiceImpl.requireDefinitionVisibleAt(definition, hospitalId);

        LabQcTarget target = targetRepository
            .findByHospitalIdAndTestDefinition_IdAndQcLevel(hospitalId, definition.getId(), level)
            .orElseGet(() -> LabQcTarget.builder()
                .hospitalId(hospitalId)
                .testDefinition(definition)
                .qcLevel(level)
                .build());
        target.setTargetMean(request.getTargetMean());
        target.setTargetSd(request.getTargetSd());
        target.setLotNumber(request.getLotNumber() == null || request.getLotNumber().isBlank()
            ? null : request.getLotNumber().trim());
        UUID approverId = roleValidator.getCurrentUserId();
        target.setApprovedById(approverId);
        target.setApprovedByDisplay(displayOf(approverId));
        target.setApprovedAt(LocalDateTime.now());
        return toDto(targetRepository.save(target));
    }

    private UUID requirePinnedHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException(RoleValidator.HOSPITAL_CONTEXT_REQUIRED);
        }
        return hospitalId;
    }

    private String displayOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
            .map(u -> {
                String full = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                    + (u.getLastName() == null ? "" : u.getLastName())).trim();
                return full.isEmpty() ? u.getUsername() : full;
            })
            .orElse(null);
    }

    static LabQcTargetResponseDTO toDto(LabQcTarget target) {
        LabTestDefinition definition = target.getTestDefinition();
        return LabQcTargetResponseDTO.builder()
            .id(target.getId())
            .hospitalId(target.getHospitalId())
            .testDefinitionId(definition != null ? definition.getId() : null)
            .testDefinitionName(definition != null ? definition.getName() : null)
            .qcLevel(target.getQcLevel() != null ? target.getQcLevel().name() : null)
            .targetMean(target.getTargetMean())
            .targetSd(target.getTargetSd())
            .lotNumber(target.getLotNumber())
            .approvedByDisplay(target.getApprovedByDisplay())
            .approvedAt(target.getApprovedAt())
            .build();
    }
}
