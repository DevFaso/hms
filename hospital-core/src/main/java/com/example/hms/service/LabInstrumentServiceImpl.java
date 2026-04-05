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
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.support.HospitalScopeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
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
    private final MessageSource messageSource;

    @Override
    public Page<LabInstrumentResponseDTO> getByHospital(UUID hospitalId, Pageable pageable, Locale locale) {
        requireHospitalScope(hospitalId, locale);
        return instrumentRepository.findByHospitalIdAndActiveTrue(hospitalId, pageable)
            .map(mapper::toDto);
    }

    @Override
    public LabInstrumentResponseDTO getById(UUID id, Locale locale) {
        LabInstrument instrument = instrumentRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage(INSTRUMENT_NOT_FOUND_KEY, new Object[]{id}, locale)));
        requireHospitalScope(instrument.getHospital().getId(), locale);
        return mapper.toDto(instrument);
    }

    @Override
    @Transactional
    public LabInstrumentResponseDTO create(UUID hospitalId, LabInstrumentRequestDTO dto, Locale locale) {
        requireHospitalScope(hospitalId, locale);

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage("hospital.notfound", new Object[]{hospitalId}, locale)));

        if (instrumentRepository.existsByHospitalIdAndSerialNumber(hospitalId, dto.getSerialNumber())) {
            throw new BusinessRuleException(
                getLocalizedMessage("instrument.duplicate.serial", new Object[]{dto.getSerialNumber()}, locale));
        }

        Department department = resolveDepartment(dto.getDepartmentId(), locale);
        LabInstrument instrument = mapper.toEntity(dto, hospital, department);
        instrument = instrumentRepository.save(instrument);
        return mapper.toDto(instrument);
    }

    @Override
    @Transactional
    public LabInstrumentResponseDTO update(UUID id, LabInstrumentRequestDTO dto, Locale locale) {
        LabInstrument instrument = instrumentRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage(INSTRUMENT_NOT_FOUND_KEY, new Object[]{id}, locale)));
        requireHospitalScope(instrument.getHospital().getId(), locale);

        // Check serial number uniqueness if changed
        if (dto.getSerialNumber() != null
                && !dto.getSerialNumber().equals(instrument.getSerialNumber())
                && instrumentRepository.existsByHospitalIdAndSerialNumber(
                    instrument.getHospital().getId(), dto.getSerialNumber())) {
            throw new BusinessRuleException(
                getLocalizedMessage("instrument.duplicate.serial", new Object[]{dto.getSerialNumber()}, locale));
        }

        Department department = resolveDepartment(dto.getDepartmentId(), locale);
        mapper.updateEntity(instrument, dto, department);
        instrument = instrumentRepository.save(instrument);
        return mapper.toDto(instrument);
    }

    @Override
    @Transactional
    public void deactivate(UUID id, Locale locale) {
        LabInstrument instrument = instrumentRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage(INSTRUMENT_NOT_FOUND_KEY, new Object[]{id}, locale)));
        requireHospitalScope(instrument.getHospital().getId(), locale);
        instrument.setActive(false);
        instrumentRepository.save(instrument);
    }

    // ── helpers ───────────────────────────────────────────────────

    private Department resolveDepartment(String departmentId, Locale locale) {
        if (departmentId == null || departmentId.isBlank()) return null;
        return departmentRepository.findById(UUID.fromString(departmentId))
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage("department.notfound", new Object[]{departmentId}, locale)));
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

    private String getLocalizedMessage(String key, Object[] args, Locale locale) {
        return messageSource.getMessage(key, args, key, locale != null ? locale : LocaleContextHolder.getLocale());
    }
}
