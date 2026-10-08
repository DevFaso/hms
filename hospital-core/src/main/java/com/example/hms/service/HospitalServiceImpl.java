package com.example.hms.service;

import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.utility.MessageUtil;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.HospitalMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.DepartmentSummaryDTO;
import com.example.hms.payload.dto.HospitalRequestDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.HospitalWithDepartmentsDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.support.HospitalScopeUtils;
import com.example.hms.utility.RoleValidator;
import org.apache.kafka.common.errors.DuplicateResourceException;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class HospitalServiceImpl implements HospitalService {

    private static final String HOSPITAL_NOT_FOUND = "hospital.notFound";

    private final HospitalRepository hospitalRepository;
    private final OrganizationRepository organizationRepository;
    private final HospitalMapper hospitalMapper;
    private final MessageSource messageSource;
    private final RoleValidator roleValidator;
    private final ProviderVerificationRepository providerVerificationRepository;


    public HospitalServiceImpl(HospitalRepository hospitalRepository,
                               OrganizationRepository organizationRepository,
                               HospitalMapper hospitalMapper,
                               MessageSource messageSource,
                               RoleValidator roleValidator,
                               ProviderVerificationRepository providerVerificationRepository) {
        this.hospitalRepository = hospitalRepository;
        this.organizationRepository = organizationRepository;
        this.hospitalMapper = hospitalMapper;
        this.messageSource = messageSource;
        this.roleValidator = roleValidator;
        this.providerVerificationRepository = providerVerificationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HospitalResponseDTO> getAllHospitals(UUID organizationId,
                                                     Boolean unassignedOnly,
                                                     String city,
                                                     String state,
                                                     Locale locale) {
        UUID organizationFilter = Boolean.TRUE.equals(unassignedOnly) ? null : organizationId;
        Boolean unassignedFilter = Boolean.TRUE.equals(unassignedOnly) ? Boolean.TRUE : null;
        String normalizedCity = normalizeQuery(city);
        String normalizedState = normalizeQuery(state);

        List<Hospital> hospitals = hospitalRepository.findAllForFilters(organizationFilter, unassignedFilter, normalizedCity, normalizedState);
        if (hospitals == null || hospitals.isEmpty()) {
            return List.of();
        }

        // No hospital-scope filtering here: the hospital directory (name,
        // address, phone) is not sensitive, and clinical staff need the full
        // list to pick destination hospitals in referral / consultation
        // workflows.  Access is already gated by SecurityConfig + controller
        // @PreAuthorize.
        return hospitals.stream()
                .filter(java.util.Objects::nonNull)
                .map(hospitalMapper::toHospitalDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public HospitalResponseDTO getHospitalById(UUID id, Locale locale) {
        Hospital hospital = getHospitalOrThrow(id);
        requireHospitalScope(hospital.getId(), locale);
        return hospitalMapper.toHospitalDTO(hospital);
    }

    @Override
    @Transactional(readOnly = true)
    public HospitalResponseDTO getHospitalByIdUnscoped(UUID id, Locale locale) {
        Hospital hospital = getHospitalOrThrow(id);
        return hospitalMapper.toHospitalDTO(hospital);
    }

    @Override
    @Transactional
    public HospitalResponseDTO createHospital(HospitalRequestDTO dto, Locale locale) {
        validateSuperAdminOrThrow(locale);
        validateAddressFields(dto, locale);

        boolean exists = hospitalRepository.existsByNameIgnoreCaseAndZipCode(dto.getName(), dto.getZipCode());
        if (exists) {
            String message = messageSource.getMessage("hospital.exists",
                    new Object[]{dto.getName()}, "Hospital already exists.", locale);
            throw new DuplicateResourceException(message);
        }

        Organization organization = null;
        if (dto.getOrganizationId() != null) {
            organization = getOrganizationOrThrow(dto.getOrganizationId());
        }

        Hospital hospital = hospitalMapper.toHospital(dto);
        if (organization != null) {
            hospital.setOrganization(organization);
        }

        // 🔐 Generate unique code before saving
        hospital.setCode(generateHospitalCode(dto.getName()));

        Hospital saved = hospitalRepository.save(hospital);
        return hospitalMapper.toHospitalDTO(saved);
    }


    @Override
    @Transactional
    public HospitalResponseDTO updateHospital(UUID id, HospitalRequestDTO dto, Locale locale) {
        validateSuperAdminOrThrow(locale);
        Hospital hospital = getClinicalHospitalOrThrow(id);
        validateAddressFields(dto, locale);
        hospitalMapper.updateHospitalFromDto(dto, hospital);
        if (dto.getOrganizationId() != null) {
            Organization organization = getOrganizationOrThrow(dto.getOrganizationId());
            hospital.setOrganization(organization);
        }
        Hospital updated = hospitalRepository.save(hospital);
        return hospitalMapper.toHospitalDTO(updated);
    }

    @Override
    @Transactional
    public void deleteHospital(UUID id, Locale locale) {
        validateSuperAdminOrThrow(locale);

        // Locked before the "never verified" check: a VERIFY of this provider
        // holds the same facility-row lock, so the two run one after the other
        // and the check reads what the verify committed.
        Hospital hospital = hospitalRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND, id));
        // A provider onboarded by mistake may be deleted while it has never
        // been verified: it has never been ACTIVE, so no user has signed in
        // there and nothing was routed to it. Its verification history goes
        // with it (V180: ON DELETE CASCADE). Once verified (even if revoked
        // since) it may have staff, orders and audit trail: it is retired
        // through the lifecycle (suspend, archive, purge), not deleted (409).
        if (hospital.isProvider() && providerVerificationRepository.existsByHospital_IdAndStatusIn(id,
                java.util.EnumSet.of(ProviderVerificationStatus.VERIFIED, ProviderVerificationStatus.REVOKED))) {
            throw new ConflictException(MessageUtil.resolveOrRaw("provider.delete.verified"));
        }

        hospitalRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HospitalResponseDTO> searchHospitals(String name, String city, String state, Boolean active, int page, int size, Locale locale) {
        Pageable pageable = PageRequest.of(page, size);
        // Slice, not Page: the callers only read the content, and a Page runs a
        // second COUNT over the same predicate whenever the page is full — which
        // the scope picker's empty-query opener always is. Blank filters mean
        // "no filter" here, so no caller has to know to pass null.
        Slice<Hospital> hospitals = hospitalRepository.searchHospitals(
            normalizeQuery(name), normalizeQuery(city), normalizeQuery(state), active, pageable);
    return hospitals.getContent().stream()
        .map(hospitalMapper::toHospitalDTO)
        .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HospitalWithDepartmentsDTO> getHospitalsWithDepartments(String hospitalQuery,
                                                                        String departmentQuery,
                                                                        Boolean activeOnly,
                                                                        Locale locale) {
        String normalizedHospitalQuery = normalizeQuery(hospitalQuery);
        String normalizedDepartmentQuery = normalizeQuery(departmentQuery);

        List<Hospital> hospitals = hospitalRepository.findAllWithDepartments(normalizedHospitalQuery, activeOnly);
        hospitals = applyHospitalScope(hospitals);
        if (hospitals.isEmpty()) {
            return List.of();
        }

        List<HospitalWithDepartmentsDTO> results = new ArrayList<>();
        for (Hospital hospital : hospitals) {
            HospitalWithDepartmentsDTO mapped = mapHospitalWithDepartments(hospital, normalizedDepartmentQuery);
            if (mapped != null) {
                results.add(mapped);
            }
        }

        results.sort(Comparator.comparing(HospitalWithDepartmentsDTO::getHospitalName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HospitalResponseDTO> getHospitalsByOrganization(UUID organizationId, Locale locale) {
        List<Hospital> hospitals = hospitalRepository.findByOrganizationIdOrderByNameAsc(organizationId);
        if (hospitals.isEmpty()) {
            if (!organizationRepository.existsById(organizationId)) {
                throw new ResourceNotFoundException("organization.notFound", organizationId);
            }
            return List.of();
        }

        List<Hospital> scopedHospitals = applyHospitalScope(hospitals);

        return scopedHospitals.stream()
                .map(hospitalMapper::toHospitalDTO)
                .toList();
    }

    @Override
    @Transactional
    public HospitalResponseDTO assignHospitalToOrganization(UUID hospitalId, UUID organizationId, Locale locale) {
        validateSuperAdminOrThrow(locale);
        Hospital hospital = getClinicalHospitalOrThrow(hospitalId);
        Organization organization = getOrganizationOrThrow(organizationId);
        hospital.setOrganization(organization);
        Hospital saved = hospitalRepository.save(hospital);
        return hospitalMapper.toHospitalDTO(saved);
    }

    @Override
    @Transactional
    public HospitalResponseDTO unassignHospitalFromOrganization(UUID hospitalId, Locale locale) {
        validateSuperAdminOrThrow(locale);
        Hospital hospital = getClinicalHospitalOrThrow(hospitalId);
        hospital.setOrganization(null);
        Hospital saved = hospitalRepository.save(hospital);
        return hospitalMapper.toHospitalDTO(saved);
    }

    /**
     * A hospital for one of the generic super-admin writes (edit, organisation
     * link and unlink). A provider facility answers exactly as an unknown id:
     * it is edited only through /super-admin/providers, whose rules (VERIFY is
     * the only way to ACTIVE; never attached to a hospital organisation,
     * provider plan AC-4 and AC-11) the generic writes would skip.
     */
    private Hospital getClinicalHospitalOrThrow(UUID id) {
        return hospitalRepository.findById(id)
                .filter(hospital -> !hospital.isProvider())
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND, id));
    }

    private Hospital getHospitalOrThrow(UUID id) {
        return hospitalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND, id));
    }

    private List<Hospital> applyHospitalScope(List<Hospital> hospitals) {
        if (hospitals == null || hospitals.isEmpty()) {
            return List.of();
        }
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (context.isSuperAdmin() || isAnonymousContext(context)) {
            return hospitals;
        }
        Set<UUID> scope = HospitalScopeUtils.resolveScope(context);
        if (scope.isEmpty()) {
            return List.of();
        }
        return hospitals.stream()
                .filter(h -> h != null && h.getId() != null && scope.contains(h.getId()))
                .toList();
    }

    private boolean hasHospitalAccess(UUID hospitalId) {
        if (hospitalId == null) {
            return false;
        }
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (context.isSuperAdmin() || isAnonymousContext(context)) {
            return true;
        }
        return HospitalScopeUtils.resolveScope(context).contains(hospitalId);
    }

    private boolean isAnonymousContext(HospitalContext context) {
        return context.getPrincipalUserId() == null && context.getPrincipalUsername() == null;
    }

    private void requireHospitalScope(UUID hospitalId, Locale locale) {
        if (!hasHospitalAccess(hospitalId)) {
            throw new AccessDeniedException(messageSource.getMessage("access.denied", null, "Access denied", locale));
        }
    }

    private Organization getOrganizationOrThrow(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("organization.notFound", id));
    }

    private void validateSuperAdminOrThrow(Locale locale) {
        if (!roleValidator.isSuperAdminFromAuth()) {
            throw new AccessDeniedException(
                    messageSource.getMessage("access.denied", null, "Access denied", locale));
        }
    }

    /**
     * Validates country-specific address rules.
     */
    private void validateAddressFields(HospitalRequestDTO dto, Locale locale) {
        String country = dto.getCountry() != null ? dto.getCountry().toUpperCase() : "";

        if (country.equals("US") || country.equals("USA")) {
            if (dto.getState() == null || dto.getState().isBlank()) {
                throw new IllegalArgumentException(
                        messageSource.getMessage("hospital.state.required", null,
                                "State is required for hospitals in the United States.", locale));
            }
            if (dto.getZipCode() == null || dto.getZipCode().isBlank()) {
                throw new IllegalArgumentException(
                        messageSource.getMessage("hospital.zipCode.required", null,
                                "Zip Code is required for hospitals in the United States.", locale));
            }
        } else {
            // Foreign Country Validation
            boolean hasValidAddress = (dto.getAddress() != null && !dto.getAddress().isBlank()) ||
                    (dto.getPoBox() != null && !dto.getPoBox().isBlank());

            if (!hasValidAddress) {
                throw new IllegalArgumentException(
                        messageSource.getMessage("hospital.address.required", null,
                                "Address or PO Box is required for foreign hospitals.", locale));
            }
        }
    }

    private HospitalWithDepartmentsDTO mapHospitalWithDepartments(Hospital hospital, String departmentQuery) {
        if (hospital == null) {
            return null;
        }

        List<DepartmentSummaryDTO> departmentSummaries = new ArrayList<>();
        if (hospital.getDepartments() != null) {
            for (Department department : hospital.getDepartments()) {
                if (department != null && matchesDepartmentQuery(department, departmentQuery)) {
                    DepartmentSummaryDTO summary = toDepartmentSummary(department, hospital);
                    if (summary != null) {
                        departmentSummaries.add(summary);
                    }
                }
            }
        }

        departmentSummaries.sort(Comparator.comparing(DepartmentSummaryDTO::getName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        if (departmentQuery != null && !departmentQuery.isEmpty() && departmentSummaries.isEmpty()) {
            return null;
        }

        return HospitalWithDepartmentsDTO.builder()
                .hospitalId(hospital.getId())
                .hospitalName(hospital.getName())
                .hospitalCode(hospital.getCode())
                .city(hospital.getCity())
                .state(hospital.getState())
                .country(hospital.getCountry())
                .active(hospital.isActive())
                .phoneNumber(hospital.getPhoneNumber())
                .email(hospital.getEmail())
                .website(hospital.getWebsite())
                .departments(departmentSummaries)
                .build();
    }

    private boolean matchesDepartmentQuery(Department department, String departmentQuery) {
        if (departmentQuery == null || departmentQuery.isEmpty()) {
            return true;
        }

        String queryLower = departmentQuery.toLowerCase(Locale.ROOT);
        return matchesDepartmentBasics(department, queryLower)
            || matchesHeadOfDepartment(department != null ? department.getHeadOfDepartment() : null, queryLower)
            || matchesHospitalForDepartment(department != null ? department.getHospital() : null, queryLower);
    }

    private boolean matchesDepartmentBasics(Department department, String queryLower) {
        if (department == null) {
            return false;
        }
        return matches(department.getName(), queryLower)
            || matches(department.getCode(), queryLower)
            || matches(department.getEmail(), queryLower)
            || matches(department.getPhoneNumber(), queryLower);
    }

    private boolean matchesHeadOfDepartment(Staff head, String queryLower) {
        if (head == null) {
            return false;
        }

        if (matches(head.getName(), queryLower)) {
            return true;
        }

        User user = head.getUser();
        if (user != null) {
            if (matches(user.getEmail(), queryLower)) {
                return true;
            }
            if (matches(buildFullName(user.getFirstName(), user.getLastName()), queryLower)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesHospitalForDepartment(Hospital hospital, String queryLower) {
        if (hospital == null) {
            return false;
        }
        return matches(hospital.getName(), queryLower) || matches(hospital.getCode(), queryLower);
    }

    private DepartmentSummaryDTO toDepartmentSummary(Department department, Hospital hospital) {
        Staff head = department.getHeadOfDepartment();
        String headName = null;
        String headEmail = null;

        if (head != null) {
            if (head.getUser() != null) {
                headEmail = head.getUser().getEmail();
                headName = buildFullName(head.getUser().getFirstName(), head.getUser().getLastName());
            }
            if ((headName == null || headName.isBlank()) && head.getName() != null) {
                headName = head.getName();
            }
        }

        int staffCount = 0;
        if (department.getStaffMembers() != null) {
            staffCount = department.getStaffMembers().size();
        }

        return DepartmentSummaryDTO.builder()
                .id(department.getId())
                .hospitalId(hospital != null ? hospital.getId() : null)
                .hospitalName(hospital != null ? hospital.getName() : null)
                .name(department.getName())
                .code(department.getCode())
                .email(department.getEmail())
                .phoneNumber(department.getPhoneNumber())
                .active(department.isActive())
                .staffCount(staffCount)
                .bedCount(department.getBedCapacity())
                .headOfDepartmentName(headName)
                .headOfDepartmentEmail(headEmail)
                .description(department.getDescription())
                .build();
    }

    private String buildFullName(String firstName, String lastName) {
        StringBuilder sb = new StringBuilder();
        if (firstName != null && !firstName.isBlank()) {
            sb.append(firstName.trim());
        }
        if (lastName != null && !lastName.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(lastName.trim());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String normalizeQuery(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean matches(String candidate, String queryLower) {
        return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(queryLower);
    }
}
