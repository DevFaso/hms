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
import jakarta.persistence.EntityNotFoundException;
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

    private static final String STUDY_NOT_FOUND = "Validation study not found";
    private static final String DEFINITION_NOT_FOUND = "Lab Test Definition not found";

    private final LabTestValidationStudyRepository repository;
    private final LabTestDefinitionRepository definitionRepository;
    private final LabTestValidationStudyMapper mapper;
    private final RoleValidator roleValidator;

    @Override
    @Transactional
    public LabTestValidationStudyResponseDTO create(UUID definitionId,
                                                    LabTestValidationStudyRequestDTO dto) {
        LabTestDefinition definition = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new EntityNotFoundException(DEFINITION_NOT_FOUND));

        LabTestValidationStudy study = mapper.toEntity(dto, definition);
        return mapper.toDto(repository.save(study));
    }

    @Override
    public LabTestValidationStudyResponseDTO getById(UUID id) {
        return mapper.toDto(repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(STUDY_NOT_FOUND)));
    }

    @Override
    public List<LabTestValidationStudyResponseDTO> getByDefinitionId(UUID definitionId) {
        if (!definitionRepository.existsById(definitionId)) {
            throw new EntityNotFoundException(DEFINITION_NOT_FOUND);
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
                .orElseThrow(() -> new EntityNotFoundException(STUDY_NOT_FOUND));
        mapper.updateEntityFromDto(dto, study);
        return mapper.toDto(repository.save(study));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new EntityNotFoundException(STUDY_NOT_FOUND);
        }
        repository.deleteById(id);
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
