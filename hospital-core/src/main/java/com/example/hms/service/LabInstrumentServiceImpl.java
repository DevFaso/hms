package com.example.hms.service;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.FieldBusinessException;
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
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.support.HospitalScopeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

// (java.time.LocalDate and FieldBusinessException are used by the date rules below.)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabInstrumentServiceImpl implements LabInstrumentService {

    private static final String INSTRUMENT_NOT_FOUND_KEY = "instrument.notFound";

    private final LabInstrumentRepository instrumentRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final LabInstrumentMapper mapper;
    private final MessageSource messageSource;

    @Override
    public Page<LabInstrumentResponseDTO> getByHospital(UUID hospitalId, Pageable pageable, Locale locale) {
        requireHospitalScope(hospitalId, locale);
        return instrumentRepository.findByHospitalIdAndActiveTrue(hospitalId, pageable)
            .map(mapper::toDto);
    }

    @Override
    public Page<LabInstrumentResponseDTO> getByHospital(UUID hospitalId, boolean includeInactive,
                                                        Pageable pageable, Locale locale) {
        if (!includeInactive) {
            return getByHospital(hospitalId, pageable, locale);
        }
        requireHospitalScope(hospitalId, locale);
        return instrumentRepository.findByHospitalId(hospitalId, pageable).map(mapper::toDto);
    }

    @Override
    @Transactional
    public LabInstrumentResponseDTO reactivate(UUID id, Locale locale) {
        LabInstrument instrument = instrumentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(INSTRUMENT_NOT_FOUND_KEY, id));
        requireOwnInstrument(instrument, id);
        if (!instrument.isActive()) {
            instrument.setActive(true);
            instrument = instrumentRepository.save(instrument);
        }
        return mapper.toDto(instrument);
    }

    /**
     * QA D4076: the dates the form now carries. A calibration or a
     * maintenance already done is not in the future, and the next one is not
     * before the last; each refusal names its field.
     */
    private static void requireCoherentDates(LabInstrumentRequestDTO dto, LabInstrument stored) {
        LocalDate today = LocalDate.now();
        requireNotFuture("installationDate", dto.getInstallationDate(), today);
        requireNotFuture("lastCalibrationDate", dto.getLastCalibrationDate(), today);
        requireNotFuture("lastMaintenanceDate", dto.getLastMaintenanceDate(), today);
        LocalDate lastCalibration = dto.getLastCalibrationDate() != null || stored == null
            ? dto.getLastCalibrationDate() : stored.getLastCalibrationDate();
        LocalDate lastMaintenance = dto.getLastMaintenanceDate() != null || stored == null
            ? dto.getLastMaintenanceDate() : stored.getLastMaintenanceDate();
        if (dto.getNextCalibrationDate() != null && lastCalibration != null
                && dto.getNextCalibrationDate().isBefore(lastCalibration)) {
            throw new FieldBusinessException("nextCalibrationDate", "instrument.date.nextBeforeLast");
        }
        if (dto.getNextMaintenanceDate() != null && lastMaintenance != null
                && dto.getNextMaintenanceDate().isBefore(lastMaintenance)) {
            throw new FieldBusinessException("nextMaintenanceDate", "instrument.date.nextBeforeLast");
        }
    }

    private static void requireNotFuture(String field, LocalDate date, LocalDate today) {
        if (date != null && date.isAfter(today)) {
            throw new FieldBusinessException(field, "instrument.date.future");
        }
    }

    @Override
    public LabInstrumentResponseDTO getById(UUID id, Locale locale) {
        LabInstrument instrument = instrumentRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(INSTRUMENT_NOT_FOUND_KEY, id));
        requireOwnInstrument(instrument, id);
        return mapper.toDto(instrument);
    }

    @Override
    @Transactional
    public LabInstrumentResponseDTO create(UUID hospitalId, LabInstrumentRequestDTO dto, Locale locale) {
        requireHospitalScope(hospitalId, locale);

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", hospitalId));

        if (instrumentRepository.existsByHospitalIdAndSerialNumber(hospitalId, dto.getSerialNumber())) {
            throw BusinessRuleException.ofKey("instrument.duplicate.serial", dto.getSerialNumber());
        }

        requireCoherentDates(dto, null);
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
        requireOwnInstrument(instrument, id);

        // Check serial number uniqueness if changed
        if (dto.getSerialNumber() != null
                && !dto.getSerialNumber().equals(instrument.getSerialNumber())
                && instrumentRepository.existsByHospitalIdAndSerialNumber(
                    instrument.getHospital().getId(), dto.getSerialNumber())) {
            throw BusinessRuleException.ofKey("instrument.duplicate.serial", dto.getSerialNumber());
        }

        requireCoherentDates(dto, instrument);
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
        requireOwnInstrument(instrument, id);
        instrument.setActive(false);
        instrumentRepository.save(instrument);
    }

    // ── helpers ───────────────────────────────────────────────────

    private Department resolveDepartment(String departmentId) {
        if (departmentId == null || departmentId.isBlank()) return null;
        Department department = departmentRepository.findById(UUID.fromString(departmentId))
            .orElseThrow(() -> new ResourceNotFoundException("department.notfound", departmentId));
        // QA r5 tenancy sweep: another hospital's row answers as unknown.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(department.getHospital(), "department.notfound", departmentId);
        return department;
    }

    /**
     * Another hospital's instrument answers exactly as an unknown id (404): a
     * 403 here told the caller that the id exists somewhere else.
     */
    private void requireOwnInstrument(LabInstrument instrument, UUID id) {
        if (instrument.getHospital() == null || !hasHospitalAccess(instrument.getHospital().getId())) {
            throw new ResourceNotFoundException(INSTRUMENT_NOT_FOUND_KEY, id);
        }
    }

    private boolean hasHospitalAccess(UUID hospitalId) {
        if (hospitalId == null) return false;
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (context.isSuperAdmin()) return true;
        return HospitalScopeUtils.resolveScope(context).contains(hospitalId);
    }

    private void requireHospitalScope(UUID hospitalId, Locale locale) {
        if (!hasHospitalAccess(hospitalId)) {
            throw new AccessDeniedException(
                messageSource.getMessage("access.denied", null, "Access denied", locale));
        }
    }

}
