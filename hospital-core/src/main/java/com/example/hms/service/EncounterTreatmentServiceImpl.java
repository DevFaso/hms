package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.EncounterTreatmentMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.Staff;
import com.example.hms.model.Treatment;
import com.example.hms.payload.dto.EncounterTreatmentRequestDTO;
import com.example.hms.payload.dto.EncounterTreatmentResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TreatmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EncounterTreatmentServiceImpl implements EncounterTreatmentService {
    private final EncounterRepository encounterRepository;
    private final TreatmentRepository treatmentRepository;
    private final StaffRepository staffRepository;
    private final EncounterTreatmentRepository encounterTreatmentRepository;
    private final EncounterTreatmentMapper mapper;

    @Override
    @Transactional
    public EncounterTreatmentResponseDTO addTreatmentToEncounter(EncounterTreatmentRequestDTO dto) {
        Encounter encounter = encounterRepository.findById(dto.getEncounterId())
                .orElseThrow(() -> new ResourceNotFoundException("Encounter not found"));
        Treatment treatment = treatmentRepository.findById(dto.getTreatmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Treatment not found"));
        Staff staff = null;
        if (dto.getStaffId() != null) {
            staff = staffRepository.findById(dto.getStaffId())
                    .orElseThrow(() -> new ResourceNotFoundException("Staff not found"));
        }
        EncounterTreatment entity = mapper.toEntity(dto, encounter, treatment, staff);
        return mapper.toDto(encounterTreatmentRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EncounterTreatmentResponseDTO> getTreatmentsByEncounter(UUID encounterId) {
        List<EncounterTreatment> list = encounterTreatmentRepository.findByEncounter_Id(encounterId);
        return list.stream().map(mapper::toDto).toList();
    }
}

