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
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.support.HospitalScopeUtils;
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
    public Page<LabInstrumentResponseDTO> getByHospital(UUID hospitalId, boolean includeInactive,
                                                        Pageable pageable, Locale locale) {
        if (!includeInactive) {
            return getByHospital(hospitalId, pageable, locale);
        }
        LabFacilityAccess.require(hospitalId, false);
        return instrumentRepository.findByHospitalId(hospitalId, pageable).map(mapper::toDto);
    }

    @Override
    @Transactional
    public LabInstrumentResponseDTO reactivate(UUID id, Locale locale) {
        LabInstrument instrument = instrumentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(INSTRUMENT_NOT_FOUND_KEY, id));
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(
            instrument.getHospital(), INSTRUMENT_NOT_FOUND_KEY, id);
        requireOwnInstrument(instrument, id);
        LabFacilityAccess.require(instrument.getHospital().getId(), true);
        if (!instrument.isActive()) {
            instrument.setActive(true);
            instrument = instrumentRepository.save(instrument);
        }
        return mapper.toDto(instrument);
    }

    @Override
    public LabInstrumentResponseDTO getById(UUID id, Locale locale) {
        LabInstrument instrument = instrumentRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(INSTRUMENT_NOT_FOUND_KEY, id));
        requireOwnInstrument(instrument, id);
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
        requireOwnInstrument(instrument, id);
        LabFacilityAccess.require(instrument.getHospital().getId(), true);
        validateChangedDates(instrument, dto);

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
        requireOwnInstrument(instrument, id);
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

    /**
     * QA r81 (#1379 F1): an edit checks only the dates it changes. The portal
     * sends every stored date back, so a row saved before these checks (a
     * future installation, a next date before the last) must still take a
     * status-only edit. A changed date is checked on its own and against the
     * other date of its pair as it will be stored.
     */
    private static void validateChangedDates(LabInstrument existing, LabInstrumentRequestDTO dto) {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Ouagadougou"));
        if (changed(dto.getInstallationDate(), existing.getInstallationDate())) {
            requireNotFuture(dto.getInstallationDate(), today, "installationDate");
        }
        boolean lastCalibrationChanged = changed(dto.getLastCalibrationDate(), existing.getLastCalibrationDate());
        boolean lastMaintenanceChanged = changed(dto.getLastMaintenanceDate(), existing.getLastMaintenanceDate());
        if (lastCalibrationChanged) requireNotFuture(dto.getLastCalibrationDate(), today, "lastCalibrationDate");
        if (lastMaintenanceChanged) requireNotFuture(dto.getLastMaintenanceDate(), today, "lastMaintenanceDate");
        if (lastCalibrationChanged || changed(dto.getNextCalibrationDate(), existing.getNextCalibrationDate())) {
            requireOrdered(effective(dto.getLastCalibrationDate(), existing.getLastCalibrationDate()),
                effective(dto.getNextCalibrationDate(), existing.getNextCalibrationDate()), "nextCalibrationDate");
        }
        if (lastMaintenanceChanged || changed(dto.getNextMaintenanceDate(), existing.getNextMaintenanceDate())) {
            requireOrdered(effective(dto.getLastMaintenanceDate(), existing.getLastMaintenanceDate()),
                effective(dto.getNextMaintenanceDate(), existing.getNextMaintenanceDate()), "nextMaintenanceDate");
        }
    }

    /** A date the request sets to a new value; null leaves the stored one. */
    private static boolean changed(java.time.LocalDate sent, java.time.LocalDate stored) {
        return sent != null && !sent.equals(stored);
    }

    private static java.time.LocalDate effective(java.time.LocalDate sent, java.time.LocalDate stored) {
        return sent != null ? sent : stored;
    }

    private static void requireNotFuture(java.time.LocalDate date, java.time.LocalDate today, String field) {
        if (date != null && date.isAfter(today)) {
            throw new com.example.hms.exception.FieldBusinessException(field, "clinical.date.future");
        }
    }

    private static void requireOrdered(java.time.LocalDate last, java.time.LocalDate next, String field) {
        if (last != null && next != null && next.isBefore(last)) {
            throw new com.example.hms.exception.FieldBusinessException(field, "lab.instrument.nextBeforeLast");
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

    /**
     * Another hospital's instrument answers exactly as an unknown id (404): a
     * 403 here told the caller that the id exists somewhere else.
     */
    private void requireOwnInstrument(LabInstrument instrument, UUID id) {
        if (instrument.getHospital() == null || !hasHospitalAccess(instrument.getHospital().getId())) {
            throw new ResourceNotFoundException(INSTRUMENT_NOT_FOUND_KEY, id);
        }
    }

    /** The acting scope decides, as in {@link LabFacilityAccess}: global view sees every hospital, a
     *  super-admin pinned to a hospital sees that one, anyone else their own hospitals. */
    private boolean hasHospitalAccess(UUID hospitalId) {
        if (hospitalId == null) return false;
        if (ActingScopeResolver.currentScope() instanceof ActingScope.Global) return true;
        UUID superAdminPin = ActingScopeResolver.superAdminPinnedHospitalIdOrNull();
        if (superAdminPin != null) return superAdminPin.equals(hospitalId);
        return HospitalScopeUtils.resolveScope(HospitalContextHolder.getContextOrEmpty()).contains(hospitalId);
    }

}
