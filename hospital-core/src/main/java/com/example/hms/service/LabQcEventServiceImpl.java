package com.example.hms.service;

import com.example.hms.enums.LabQcEventLevel;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabQcEventMapper;
import com.example.hms.model.LabQcEvent;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabQcEventRequestDTO;
import com.example.hms.payload.dto.LabQcEventResponseDTO;
import com.example.hms.payload.dto.LabQcSummaryDTO;
import com.example.hms.repository.LabQcEventRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LabQcEventServiceImpl implements LabQcEventService {

    private final LabQcEventRepository qcEventRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final LabQcEventMapper qcEventMapper;
    private final RoleValidator roleValidator;

    @Override
    @Transactional
    public LabQcEventResponseDTO recordQcEvent(LabQcEventRequestDTO request, Locale locale) {
        if (request.getHospitalId() == null) {
            throw new BusinessException("hospitalId is required when recording a QC event.");
        }
        if (request.getMeasuredValue() == null || request.getExpectedValue() == null) {
            throw new BusinessException("measuredValue and expectedValue are required.");
        }

        LabQcEventLevel level;
        try {
            level = LabQcEventLevel.valueOf(request.getQcLevel().trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new BusinessException("Unknown QC level: " + request.getQcLevel() +
                ". Accepted values: LOW_CONTROL, HIGH_CONTROL");
        }

        LabTestDefinition testDef = null;
        if (request.getTestDefinitionId() != null) {
            testDef = labTestDefinitionRepository.findById(request.getTestDefinitionId())
                .orElseThrow(() -> new ResourceNotFoundException("labtestdef.notfound"));
        }

        // Auto-determine pass/fail: within 10% deviation is considered "passed"
        double measured = request.getMeasuredValue().doubleValue();
        double expected = request.getExpectedValue().doubleValue();
        boolean passed = expected != 0 && Math.abs((measured - expected) / expected) <= 0.10;

        LabQcEvent event = LabQcEvent.builder()
            .hospitalId(request.getHospitalId())
            .analyzerId(request.getAnalyzerId())
            .testDefinition(testDef)
            .qcLevel(level)
            .measuredValue(request.getMeasuredValue())
            .expectedValue(request.getExpectedValue())
            .passed(passed)
            .recordedAt(request.getRecordedAt() != null ? request.getRecordedAt() : LocalDateTime.now())
            .recordedById(roleValidator.getCurrentUserId())
            .notes(request.getNotes())
            .build();

        return qcEventMapper.toResponseDTO(qcEventRepository.save(event));
    }

    @Override
    @Transactional(readOnly = true)
    public LabQcEventResponseDTO getQcEventById(UUID id, Locale locale) {
        LabQcEvent event = qcEventRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("labqcevent.notfound"));
        return qcEventMapper.toResponseDTO(event);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LabQcEventResponseDTO> getQcEventsByHospital(UUID hospitalId, Pageable pageable, Locale locale) {
        UUID scopedHospitalId = hospitalId;
        if (scopedHospitalId == null) {
            scopedHospitalId = roleValidator.requireActiveHospitalId();
        }
        if (scopedHospitalId == null) {
            // Super-admin: return all
            return qcEventRepository.findAll(pageable).map(qcEventMapper::toResponseDTO);
        }
        return qcEventRepository.findByHospitalId(scopedHospitalId, pageable)
            .map(qcEventMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LabQcEventResponseDTO> getQcEventsByDefinition(UUID testDefinitionId, Pageable pageable, Locale locale) {
        UUID scopedHospitalId = roleValidator.requireActiveHospitalId();
        if (scopedHospitalId == null) {
            // SUPER_ADMIN: no hospital scoping — return across all hospitals
            return qcEventRepository.findByTestDefinitionId(testDefinitionId, pageable)
                .map(qcEventMapper::toResponseDTO);
        }
        return qcEventRepository.findByTestDefinitionIdAndHospitalId(testDefinitionId, scopedHospitalId, pageable)
            .map(qcEventMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabQcSummaryDTO> getQcSummary(Locale locale) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        List<Object[]> rows = (hospitalId != null)
            ? qcEventRepository.findQcSummaryByHospitalId(hospitalId)
            : qcEventRepository.findQcSummaryAll();

        return rows.stream().map(row -> {
            UUID testDefId   = (UUID) row[0];
            String testName  = (String) row[1];
            long total       = ((Number) row[2]).longValue();
            long passed      = ((Number) row[3]).longValue();
            long failed      = ((Number) row[4]).longValue();
            LocalDateTime lastDate = (LocalDateTime) row[5];
            double passRate  = total > 0 ? (double) passed / total * 100.0 : 0.0;

            return LabQcSummaryDTO.builder()
                .testDefinitionId(testDefId)
                .testName(testName)
                .totalEvents(total)
                .passedEvents(passed)
                .failedEvents(failed)
                .passRate(Math.round(passRate * 100.0) / 100.0)
                .lastEventDate(lastDate)
                .build();
        }).toList();
    }
}
