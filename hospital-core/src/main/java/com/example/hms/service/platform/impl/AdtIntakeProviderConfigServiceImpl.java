package com.example.hms.service.platform.impl;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.platform.AdtIntakeProviderConfig;
import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigRequestDTO;
import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.platform.AdtIntakeProviderConfigRepository;
import com.example.hms.service.platform.AdtIntakeProviderConfigService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdtIntakeProviderConfigServiceImpl
    implements AdtIntakeProviderConfigService {

    private static final String CONFIG_NOT_FOUND = "adt.intakeconfig.notfound";
    private static final String HOSPITAL_NOT_FOUND = "hospital.notfound";
    /** QA D872: the default reason, written in the language of the admin who saved the config. */
    private static final String DEFAULT_CHIEF_COMPLAINT_KEY = "adtIntakeProviderConfig.defaultChiefComplaint.default";
    private static final String DEFAULT_CHIEF_COMPLAINT = "Admitted automatically from an ADT message";

    private final AdtIntakeProviderConfigRepository repository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final MessageSource messageSource;

    @Override
    @Transactional
    public AdtIntakeProviderConfigResponseDTO upsert(
        AdtIntakeProviderConfigRequestDTO request, Locale locale
    ) {
        Hospital hospital = loadHospital(request.hospitalId());
        requireReferencesAt(request, hospital.getId());
        AdtIntakeProviderConfig entity = repository
            .findByHospital_Id(request.hospitalId())
            .orElseGet(AdtIntakeProviderConfig::new);
        entity.setHospital(hospital);
        applyRequestToEntity(request, entity, locale);
        AdtIntakeProviderConfig saved = repository.save(entity);
        log.info("ADT intake-provider config upserted id={} hospital={} enabled={} A04Assignment={}",
            saved.getId(), hospital.getId(), saved.isEnabled(), saved.getDefaultAssignmentId());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AdtIntakeProviderConfigResponseDTO getById(UUID id, Locale locale) {
        return repository.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException(CONFIG_NOT_FOUND, id));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdtIntakeProviderConfigResponseDTO> findByHospital(UUID hospitalId) {
        return repository.findByHospital_Id(hospitalId).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdtIntakeProviderConfigResponseDTO> findAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public void delete(UUID id, Locale locale) {
        AdtIntakeProviderConfig entity = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(CONFIG_NOT_FOUND, id));
        // QA r5 tenancy sweep: a write loads under the tenant guard.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(entity.getHospital(), CONFIG_NOT_FOUND, id);
        repository.delete(entity);
        log.info("ADT intake-provider config deleted id={} hospital={}",
            id, entity.getHospital() != null ? entity.getHospital().getId() : null);
    }

    private Hospital loadHospital(UUID hospitalId) {
        return hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND, hospitalId));
    }

    /**
     * QA D852: every id the config carries is checked against the hospital it
     * is for. The admitting provider must be an active staff member there
     * holding a doctor, physician or surgeon role; the department and the
     * default assignment must belong there too. Before, any staff id was
     * saved, and the auto-create path only discovered a foreign or
     * non-physician id when an ADT message arrived.
     */
    private void requireReferencesAt(AdtIntakeProviderConfigRequestDTO request, UUID hospitalId) {
        Staff provider = staffRepository.findById(request.admittingProviderId())
            .filter(s -> s.isActive() && s.getHospital() != null
                && hospitalId.equals(s.getHospital().getId()))
            .orElseThrow(() -> new FieldBusinessException("admittingProviderId",
                "adtIntakeProviderConfig.admittingProvider.notAtHospital"));
        UUID userId = provider.getUser() != null ? provider.getUser().getId() : null;
        if (userId == null || !assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                userId, hospitalId, ADMITTING_ROLE_CODES)) {
            throw new FieldBusinessException("admittingProviderId",
                "adtIntakeProviderConfig.admittingProvider.notPhysician");
        }
        if (request.departmentId() != null) {
            Department department = departmentRepository.findById(request.departmentId()).orElse(null);
            if (department == null || department.getHospital() == null
                    || !hospitalId.equals(department.getHospital().getId())) {
                throw new FieldBusinessException("departmentId",
                    "adtIntakeProviderConfig.department.notAtHospital");
            }
        }
        if (request.defaultAssignmentId() != null) {
            UserRoleHospitalAssignment assignment =
                assignmentRepository.findById(request.defaultAssignmentId()).orElse(null);
            if (assignment == null || !Boolean.TRUE.equals(assignment.getActive())
                    || assignment.getHospital() == null
                    || !hospitalId.equals(assignment.getHospital().getId())) {
                throw new FieldBusinessException("defaultAssignmentId",
                    "adtIntakeProviderConfig.assignment.notAtHospital");
            }
        }
    }

    private void applyRequestToEntity(
        AdtIntakeProviderConfigRequestDTO request, AdtIntakeProviderConfig entity, Locale locale
    ) {
        entity.setAdmittingProviderId(request.admittingProviderId());
        entity.setDepartmentId(request.departmentId());
        entity.setDefaultAssignmentId(request.defaultAssignmentId());
        entity.setDefaultAdmissionType(request.defaultAdmissionType());
        entity.setDefaultAcuityLevel(request.defaultAcuityLevel());
        entity.setDefaultEncounterType(request.defaultEncounterType());
        String chiefComplaint = request.defaultChiefComplaint();
        entity.setDefaultChiefComplaint(
            chiefComplaint == null || chiefComplaint.isBlank()
                ? messageSource.getMessage(DEFAULT_CHIEF_COMPLAINT_KEY, null, DEFAULT_CHIEF_COMPLAINT,
                    locale != null ? locale : org.springframework.context.i18n.LocaleContextHolder.getLocale())
                : chiefComplaint.trim());
        // Default to disabled when the field is absent; admin must opt
        // in explicitly so we never flip auto-create on by accident on
        // first-touch.
        entity.setEnabled(Boolean.TRUE.equals(request.enabled()));
    }

    private AdtIntakeProviderConfigResponseDTO toResponse(AdtIntakeProviderConfig entity) {
        Hospital hospital = entity.getHospital();
        return new AdtIntakeProviderConfigResponseDTO(
            entity.getId(),
            hospital != null ? hospital.getId() : null,
            hospital != null ? hospital.getName() : null,
            entity.getAdmittingProviderId(),
            entity.getDepartmentId(),
            entity.getDefaultAssignmentId(),
            entity.getDefaultAdmissionType(),
            entity.getDefaultAcuityLevel(),
            entity.getDefaultEncounterType(),
            entity.getDefaultChiefComplaint(),
            entity.isEnabled(),
            entity.getCreatedAt(),
            entity.getUpdatedAt());
    }
}
