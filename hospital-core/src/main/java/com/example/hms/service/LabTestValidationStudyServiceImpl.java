package com.example.hms.service;

import com.example.hms.mapper.LabTestValidationStudyMapper;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestValidationStudy;
import com.example.hms.payload.dto.LabTestValidationStudyRequestDTO;
import com.example.hms.payload.dto.LabTestValidationStudyResponseDTO;
import com.example.hms.payload.dto.LabValidationSummaryDTO;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.LabTestValidationStudyRepository;
import com.example.hms.utility.RoleValidator;
import com.example.hms.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabTestValidationStudyServiceImpl implements LabTestValidationStudyService {

    private static final String STUDY_NOT_FOUND = "labvalidationstudy.notfound";
    private static final String DEFINITION_NOT_FOUND = "labtestdefinition.notfound";

    private final LabTestValidationStudyRepository repository;
    private final LabTestDefinitionRepository definitionRepository;
    private final LabTestValidationStudyMapper mapper;
    private final RoleValidator roleValidator;

    @Override
    @Transactional
    public LabTestValidationStudyResponseDTO create(UUID definitionId,
                                                    LabTestValidationStudyRequestDTO dto) {
        LabTestDefinition definition = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new ResourceNotFoundException(DEFINITION_NOT_FOUND));
        // QA r5 tenancy sweep: a write loads under the tenant guard.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(definition.getHospital(), DEFINITION_NOT_FOUND, definitionId);

        LabTestValidationStudy study = mapper.toEntity(dto, definition);
        // QA D503 class: the person who performed the study is the caller.
        study.setPerformedByUserId(com.example.hms.security.CallerIdentity.resolveActorUserId(dto.getPerformedByUserId()));
        return mapper.toDto(repository.save(study));
    }

    @Override
    public LabTestValidationStudyResponseDTO getById(UUID id) {
        LabTestValidationStudy study = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(STUDY_NOT_FOUND));
        requireOwned(study, id);
        return mapper.toDto(study);
    }

    /** A study is read and written under the tenant guard of its test definition's hospital. */
    private void requireOwned(LabTestValidationStudy study, UUID id) {
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(
                study.getLabTestDefinition() == null ? null : study.getLabTestDefinition().getHospital(),
                STUDY_NOT_FOUND, id);
    }

    @Override
    public List<LabTestValidationStudyResponseDTO> getByDefinitionId(UUID definitionId) {
        if (!definitionRepository.existsById(definitionId)) {
            throw new ResourceNotFoundException(DEFINITION_NOT_FOUND);
        }
        return repository.findByLabTestDefinition_IdOrderByStudyDateDesc(definitionId)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Override
    @Transactional
    public LabTestValidationStudyResponseDTO update(UUID id, LabTestValidationStudyRequestDTO dto) {
        LabTestValidationStudy study = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(STUDY_NOT_FOUND));
        requireOwned(study, id);
        UUID claimed = dto.getPerformedByUserId();
        // QA D503 class: keeping the recorded performer is fine; naming a new one must name the caller.
        UUID performer = claimed != null && claimed.equals(study.getPerformedByUserId())
                ? claimed
                : com.example.hms.security.CallerIdentity.resolveActorUserId(claimed);
        mapper.updateEntityFromDto(dto, study);
        study.setPerformedByUserId(performer);
        return mapper.toDto(repository.save(study));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        LabTestValidationStudy study = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(STUDY_NOT_FOUND));
        requireOwned(study, id);
        repository.delete(study);
    }

    @Override
    public List<LabValidationSummaryDTO> getValidationSummary() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        List<Object[]> rows = hospitalId == null
                ? repository.findValidationSummaryAll()
                : repository.findValidationSummaryByHospitalId(hospitalId);
        return rows.stream().map(r -> {
            long total = ((Number) r[3]).longValue();
            long passed = ((Number) r[4]).longValue();
            double passRate = total > 0 ? (double) passed / total * 100.0 : 0.0;
            return LabValidationSummaryDTO.builder()
                    .testDefinitionId((UUID) r[0])
                    .testName((String) r[1])
                    .testCode((String) r[2])
                    .totalStudies(total)
                    .passedStudies(passed)
                    .failedStudies(((Number) r[5]).longValue())
                    .passRate(passRate)
                    .lastStudyDate(r[6] != null ? (LocalDate) r[6] : null)
                    .build();
        }).toList();
    }
}
