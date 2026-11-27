package com.example.hms.service;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabInstrumentMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabInstrument;
import com.example.hms.payload.dto.LabInstrumentRequestDTO;
import com.example.hms.payload.dto.LabInstrumentResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabInstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabInstrumentServiceImpl implements LabInstrumentService {

    private static final String INSTRUMENT_NOT_FOUND_KEY = "instrument.notFound";

    private final LabInstrumentRepository instrumentRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final LabInstrumentMapper mapper;

    @Override
    public Page<LabInstrumentResponseDTO> getByHospital(UUID hospitalId, Pageable pageable, Locale locale) {
        LabFacilityAccess.require(hospitalId, false);
        return instrumentRepository.findByHospitalIdAndActiveTrue(hospitalId, pageable)
            .map(mapper::toDto);
    }

    @Override
    public LabInstrumentResponseDTO getById(UUID id, Locale locale) {
        LabInstrument instrument = instrumentRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(INSTRUMENT_NOT_FOUND_KEY, id));
        LabFacilityAccess.require(instrument.getHospital().getId(), false);
        return mapper.toDto(instrument);
    }

    @Override
    @Transactional
    public LabInstrumentResponseDTO create(UUID hospitalId, LabInstrumentRequestDTO dto, Locale locale) {
        LabFacilityAccess.require(hospitalId, true);
        validateDates(dto);

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", hospitalId));

        if (instrumentRepository.existsByHospitalIdAndSerialNumber(hospitalId, dto.getSerialNumber())) {
            throw BusinessRuleException.ofKey("instrument.duplicate.serial", dto.getSerialNumber());
        }

        Department department = resolveDepartment(dto.getDepartmentId());
        LabInstrument instrument = mapper.toEntity(dto, hospital, department);
        instrument = instrumentRepository.save(instrument);
        return mapper.toDto(instrument);
    }

    @Override
    @Transactional
    public LabInstrumentResponseDTO update(UUID id, LabInstrumentRequestDTO dto, Locale locale) {
        LabInstrument instrument = instrumentRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(INSTRUMENT_NOT_FOUND_KEY, id));
        LabFacilityAccess.require(instrument.getHospital().getId(), true);
        validateDates(dto);
        validateEffectivePairs(instrument, dto);

        // Check serial number uniqueness if changed
        if (dto.getSerialNumber() != null
                && !dto.getSerialNumber().equals(instrument.getSerialNumber())
                && instrumentRepository.existsByHospitalIdAndSerialNumber(
                    instrument.getHospital().getId(), dto.getSerialNumber())) {
            throw BusinessRuleException.ofKey("instrument.duplicate.serial", dto.getSerialNumber());
        }

        Department department = resolveDepartment(dto.getDepartmentId());
        mapper.updateEntity(instrument, dto, department);
        instrument = instrumentRepository.save(instrument);
        return mapper.toDto(instrument);
    }

    @Override
    @Transactional
    public void deactivate(UUID id, Locale locale) {
        LabInstrument instrument = instrumentRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(INSTRUMENT_NOT_FOUND_KEY, id));
        LabFacilityAccess.require(instrument.getHospital().getId(), true);
        instrument.setActive(false);
        instrumentRepository.save(instrument);
    }

    // ── helpers ───────────────────────────────────────────────────

    private static void validateDates(LabInstrumentRequestDTO dto) {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Ouagadougou"));
        requireNotFuture(dto.getInstallationDate(), today, "installationDate");
        requireNotFuture(dto.getLastCalibrationDate(), today, "lastCalibrationDate");
        requireNotFuture(dto.getLastMaintenanceDate(), today, "lastMaintenanceDate");
        requireOrdered(dto.getLastCalibrationDate(), dto.getNextCalibrationDate(), "nextCalibrationDate");
        requireOrdered(dto.getLastMaintenanceDate(), dto.getNextMaintenanceDate(), "nextMaintenanceDate");
    }

    private static void validateEffectivePairs(LabInstrument existing, LabInstrumentRequestDTO dto) {
        if (dto.getLastCalibrationDate() != null || dto.getNextCalibrationDate() != null) {
            requireOrdered(dto.getLastCalibrationDate() != null ? dto.getLastCalibrationDate() : existing.getLastCalibrationDate(),
                dto.getNextCalibrationDate() != null ? dto.getNextCalibrationDate() : existing.getNextCalibrationDate(),
                "nextCalibrationDate");
        }
        if (dto.getLastMaintenanceDate() != null || dto.getNextMaintenanceDate() != null) {
            requireOrdered(dto.getLastMaintenanceDate() != null ? dto.getLastMaintenanceDate() : existing.getLastMaintenanceDate(),
                dto.getNextMaintenanceDate() != null ? dto.getNextMaintenanceDate() : existing.getNextMaintenanceDate(),
                "nextMaintenanceDate");
        }
    }

    private static void requireNotFuture(java.time.LocalDate date, java.time.LocalDate today, String field) {
        if (date != null && date.isAfter(today)) {
            throw new com.example.hms.exception.FieldBusinessException(field, "clinical.date.future");
        }
    }

    private static void requireOrdered(java.time.LocalDate last, java.time.LocalDate next, String field) {
        if (last != null && next != null && next.isBefore(last)) {
            throw new com.example.hms.exception.FieldBusinessException(field, "query.dateRange.reversed");
        }
    }

    private Department resolveDepartment(String departmentId) {
        if (departmentId == null || departmentId.isBlank()) return null;
        Department department = departmentRepository.findById(UUID.fromString(departmentId))
            .orElseThrow(() -> new ResourceNotFoundException("department.notfound", departmentId));
        // QA r5 tenancy sweep: another hospital's row answers as unknown.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(department.getHospital(), "department.notfound", departmentId);
        return department;
    }

}
