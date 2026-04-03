package com.example.hms.service;

import com.example.hms.mapper.LabTestValidationStudyMapper;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestValidationStudy;
import com.example.hms.payload.dto.LabTestValidationStudyRequestDTO;
import com.example.hms.payload.dto.LabTestValidationStudyResponseDTO;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.LabTestValidationStudyRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
