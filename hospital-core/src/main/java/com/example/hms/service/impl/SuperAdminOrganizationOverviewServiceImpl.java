package com.example.hms.service.impl;

import com.example.hms.enums.SecurityRuleType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.model.embedded.PlatformOwnership;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.StaffResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationHierarchyResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationHierarchyResponseDTO.HospitalHierarchyDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationHierarchyResponseDTO.OrganizationHierarchyDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationHierarchyResponseDTO.PatientSummaryDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationHierarchyResponseDTO.StaffSummaryDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationsSummaryDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationsSummaryDTO.ComplianceAlertDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationsSummaryDTO.DirectoryEntryDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationsSummaryDTO.ImplementationStatusDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationsSummaryDTO.LocalizationDefaultsDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.service.OrganizationSecurityService;
import com.example.hms.service.PatientService;
import com.example.hms.service.SuperAdminOrganizationOverviewService;
import com.example.hms.service.StaffService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import static java.util.function.Predicate.not;


@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SuperAdminOrganizationOverviewServiceImpl implements SuperAdminOrganizationOverviewService {

    private static final int ALERT_DAYS_WARNING = 30;
    private static final int ALERT_DAYS_CRITICAL = 7;
    private static final String SEVERITY_CRITICAL = "critical";
    private static final String SEVERITY_WARNING = "warning";
    private static final Map<String, String> ISO3_TO_ISO2_COUNTRY_CACHE = buildIso3ToIso2Cache();

    private final OrganizationRepository organizationRepository;
    private final OrganizationSecurityPolicyRepository securityPolicyRepository;
    private final OrganizationSecurityService organizationSecurityService;
    private final StaffService staffService;
    private final PatientService patientService;

    @Override
    public SuperAdminOrganizationsSummaryDTO getOrganizationsSummary() {
        List<Organization> organizations = organizationRepository.findAll();

        List<DirectoryEntryDTO> directory = organizations.stream()
            .map(this::mapDirectoryEntry)
            .toList();

        List<ComplianceAlertDTO> complianceAlerts = organizations.stream()
            .flatMap(org -> buildComplianceAlerts(org).stream())
            .toList();

        List<LocalizationDefaultsDTO> localizationDefaults = organizations.stream()
            .map(this::mapLocalizationDefaults)
            .toList();

        ImplementationStatusDTO status = buildImplementationStatus(directory, complianceAlerts, localizationDefaults);

        return SuperAdminOrganizationsSummaryDTO.builder()
            .directory(directory)
            .complianceAlerts(complianceAlerts)
            .localizationDefaults(localizationDefaults)
            .implementationStatus(status)
            .build();
    }

    @Override
    public SuperAdminOrganizationHierarchyResponseDTO getOrganizationHierarchy(
        boolean includeStaff,
        boolean includePatients,
        Boolean activeOnly,
        String search,
        int staffLimit,
        int patientLimit,
        Locale locale
    ) {
        int normalizedStaffLimit = normalizeLimit(staffLimit);
        int normalizedPatientLimit = normalizeLimit(patientLimit);
        String normalizedSearch = normalizeSearch(search);

        HierarchyRequestContext context = new HierarchyRequestContext(
            includeStaff,
            includePatients,
            activeOnly,
            normalizedStaffLimit,
            normalizedPatientLimit,
            normalizedSearch
        );

        List<OrganizationHierarchyDTO> organizations = organizationRepository.findAll().stream()
            .filter(org -> activeOnly == null || org.isActive() == activeOnly)
            .sorted(Comparator.comparing(this::resolveOrganizationSortKey, String.CASE_INSENSITIVE_ORDER))
            .map(org -> mapOrganizationHierarchy(org, context))
            .flatMap(Optional::stream)
            .toList();

        long totalHospitals = organizations.stream()
            .mapToLong(org -> org.getHospitals().size())
            .sum();
        long totalStaff = organizations.stream()
            .mapToLong(org -> org.getHospitals().stream().mapToLong(HospitalHierarchyDTO::getStaffCount).sum())
            .sum();
        long totalPatients = organizations.stream()
            .mapToLong(org -> org.getHospitals().stream().mapToLong(HospitalHierarchyDTO::getPatientCount).sum())
            .sum();

        return SuperAdminOrganizationHierarchyResponseDTO.builder()
            .organizations(organizations)
            .totalOrganizations(organizations.size())
            .totalHospitals(totalHospitals)
            .totalStaff(totalStaff)
            .totalPatients(totalPatients)
            .includeStaff(includeStaff)
            .includePatients(includePatients)
            .activeOnly(activeOnly)
            .search(normalizedSearch)
            .staffLimit(normalizedStaffLimit)
            .patientLimit(normalizedPatientLimit)
            .build();
    }

    private Optional<OrganizationHierarchyDTO> mapOrganizationHierarchy(
        Organization organization,
        HierarchyRequestContext context
    ) {
        boolean organizationMatches = context.normalizedSearch() == null
            || matchesOrganizationSearch(organization, context.normalizedSearch());

        List<HospitalHierarchyDTO> hospitals = organization.getHospitals().stream()
            .filter(Objects::nonNull)
            .filter(hospital -> context.activeOnly() == null || hospital.isActive() == context.activeOnly())
            .sorted(Comparator.comparing(this::resolveHospitalSortKey, String.CASE_INSENSITIVE_ORDER))
            .map(hospital -> mapHospitalHierarchy(
                hospital,
                organizationMatches,
                context
            ))
            .flatMap(Optional::stream)
            .toList();

        if (context.normalizedSearch() != null && !organizationMatches && hospitals.isEmpty()) {
            return Optional.empty();
        }

        long totalStaff = hospitals.stream().mapToLong(HospitalHierarchyDTO::getStaffCount).sum();
        long totalPatients = hospitals.stream().mapToLong(HospitalHierarchyDTO::getPatientCount).sum();

        OrganizationHierarchyDTO node = OrganizationHierarchyDTO.builder()
            .organizationId(organization.getId())
            .organizationName(organization.getName())
            .organizationCode(organization.getCode())
            .organizationType(organization.getType() != null ? organization.getType().name() : null)
            .active(organization.isActive())
            .hospitals(hospitals)
            .totalHospitals(hospitals.size())
            .totalStaff(totalStaff)
            .totalPatients(totalPatients)
            .build();
        return Optional.of(node);
    }

    private Optional<HospitalHierarchyDTO> mapHospitalHierarchy(
        Hospital hospital,
        boolean organizationMatches,
        HierarchyRequestContext context
    ) {
        boolean hospitalMatches = context.normalizedSearch() == null
            || matchesHospitalSearch(hospital, context.normalizedSearch());
        if (context.normalizedSearch() != null && !organizationMatches && !hospitalMatches) {
            return Optional.empty();
        }

        List<StaffSummaryDTO> staffSummaries = List.of();
        long staffCount = 0L;
        if (context.includeStaff()) {
            PageRequest staffPageRequest = PageRequest.of(0, context.staffLimit());
            Page<StaffResponseDTO> staffPage = Boolean.TRUE.equals(context.activeOnly())
                ? staffService.getStaffByHospitalIdAndActiveTrue(hospital.getId(), staffPageRequest)
                : staffService.getStaffByHospitalId(hospital.getId(), staffPageRequest);
            staffSummaries = staffPage.getContent().stream()
                .map(this::toStaffSummary)
                .toList();
            staffCount = staffPage.getTotalElements();
        }

        List<PatientSummaryDTO> patientSummaries = List.of();
        long patientCount = 0L;
        if (context.includePatients()) {
            PageRequest patientPageRequest = PageRequest.of(0, context.patientLimit());
            Page<PatientResponseDTO> patientPage = patientService.getPatientPageByHospital(
                hospital.getId(),
                context.activeOnly(),
                patientPageRequest
            );
            patientSummaries = patientPage.getContent().stream()
                .map(this::toPatientSummary)
                .toList();
            patientCount = patientPage.getTotalElements();
        }

        HospitalHierarchyDTO node = HospitalHierarchyDTO.builder()
            .hospitalId(hospital.getId())
            .hospitalName(hospital.getName())
            .hospitalCode(hospital.getCode())
            .city(hospital.getCity())
            .state(hospital.getState())
            .active(hospital.isActive())
            .staffCount(staffCount)
            .patientCount(patientCount)
            .staff(staffSummaries)
            .patients(patientSummaries)
            .build();
        return Optional.of(node);
    }

    private StaffSummaryDTO toStaffSummary(StaffResponseDTO dto) {
        if (dto == null) {
            return StaffSummaryDTO.builder().build();
        }
        return StaffSummaryDTO.builder()
            .id(parseUuid(dto.getId()))
            .fullName(resolveStaffName(dto))
            .role(dto.getRoleName())
            .jobTitle(dto.getJobTitle() != null ? dto.getJobTitle().name() : null)
            .email(dto.getEmail())
            .phoneNumber(dto.getPhoneNumber())
            .active(dto.isActive())
            .build();
    }

    private PatientSummaryDTO toPatientSummary(PatientResponseDTO dto) {
        if (dto == null) {
            return PatientSummaryDTO.builder().build();
        }
        return PatientSummaryDTO.builder()
            .id(dto.getId())
            .fullName(resolvePatientName(dto))
            .mrn(dto.getMrn())
            .email(dto.getEmail())
            .phoneNumber(dto.getPhoneNumberPrimary())
            .dateOfBirth(dto.getDateOfBirth())
            .active(dto.isActive())
            .build();
    }

    private String resolvePatientName(PatientResponseDTO dto) {
        String first = safeTrim(dto.getFirstName());
        String last = safeTrim(dto.getLastName());
        if (first != null && last != null) {
            return first + " " + last;
        }
        if (first != null) {
            return first;
        }
        if (last != null) {
            return last;
        }
        return "Patient";
    }

    private String resolveStaffName(StaffResponseDTO dto) {
        String name = safeTrim(dto.getName());
        if (name != null) {
            return name;
        }
        String username = safeTrim(dto.getUsername());
        return username != null ? username : "Staff";
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        if (limit > 100) {
            return 100;
        }
        return limit;
    }

    private String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ENGLISH);
    }

    private boolean matchesOrganizationSearch(Organization organization, String normalizedSearch) {
        if (normalizedSearch == null) {
            return true;
        }
        return Stream.of(organization.getName(), organization.getCode(), organization.getDescription())
            .filter(Objects::nonNull)
            .map(this::safeLowercase)
            .anyMatch(value -> value.contains(normalizedSearch));
    }

    private boolean matchesHospitalSearch(Hospital hospital, String normalizedSearch) {
        if (normalizedSearch == null) {
            return true;
        }
        return Stream.of(hospital.getName(), hospital.getCode(), hospital.getCity(), hospital.getState())
            .filter(Objects::nonNull)
            .map(this::safeLowercase)
            .anyMatch(value -> value.contains(normalizedSearch));
    }

    private String resolveOrganizationSortKey(Organization organization) {
        return safeLowercase(organization.getName() != null ? organization.getName() : organization.getCode());
    }

    private String resolveHospitalSortKey(Hospital hospital) {
        String candidate = hospital.getName();
        if (candidate == null || candidate.isBlank()) {
            candidate = hospital.getCode() != null ? hospital.getCode() : hospital.getId().toString();
        }
        return candidate.toLowerCase(Locale.ENGLISH);
    }

    private String safeLowercase(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ENGLISH);
    }

    private String safeTrim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private record HierarchyRequestContext(
        boolean includeStaff,
        boolean includePatients,
        Boolean activeOnly,
        int staffLimit,
        int patientLimit,
        String normalizedSearch
    ) { }

    private DirectoryEntryDTO mapDirectoryEntry(Organization organization) {
        String statusLabel = organization.isActive() ? "Active" : "Suspended";

        List<String> complianceViolations = fetchComplianceViolations(organization.getId());
        String posture = resolveCompliancePosture(complianceViolations);

        String contactName = resolveContactName(organization);
        String contactEmail = resolveContactEmail(organization);

        return DirectoryEntryDTO.builder()
            .id(organization.getId())
            .name(organization.getName())
            .status(statusLabel)
            .compliancePosture(posture)
            .primaryContactName(contactName)
            .primaryContactEmail(contactEmail)
            .build();
    }

    private List<String> fetchComplianceViolations(UUID organizationId) {
        try {
            return organizationSecurityService.validateSecurityCompliance(organizationId);
        } catch (RuntimeException ex) {
            log.warn("Failed to resolve compliance violations for organization {}: {}", organizationId, ex.getMessage());
            return List.of();
        }
    }

    private String resolveCompliancePosture(List<String> violations) {
        if (violations.isEmpty()) {
            return "Compliant";
        }
        boolean hasCritical = violations.stream().anyMatch(msg -> msg.toLowerCase(Locale.ENGLISH).contains("missing"));
        return hasCritical ? "Action required" : "Review recommended";
    }

    private String resolveContactName(Organization organization) {
        return Optional.ofNullable(organization.getOwnership())
            .map(ownership -> {
                if (ownership.getDataSteward() != null && !ownership.getDataSteward().isBlank()) {
                    return ownership.getDataSteward();
                }
                if (ownership.getOwnerTeam() != null && !ownership.getOwnerTeam().isBlank()) {
                    return ownership.getOwnerTeam();
                }
                return null;
            })
            .orElse("Operations Team");
    }

    private String resolveContactEmail(Organization organization) {
        if (organization.getPrimaryContactEmail() != null && !organization.getPrimaryContactEmail().isBlank()) {
            return organization.getPrimaryContactEmail();
        }

        return Optional.ofNullable(organization.getOwnership())
            .map(PlatformOwnership::getOwnerContactEmail)
            .filter(email -> email != null && !email.isBlank())
            .orElseGet(() -> "info@" + sanitizeIdentifier(organization.getCode(), organization.getName()) + ".example.org");
    }

    private List<ComplianceAlertDTO> buildComplianceAlerts(Organization organization) {
        List<ComplianceAlertDTO> alerts = new ArrayList<>();

        List<OrganizationSecurityPolicy> policies = securityPolicyRepository.findByOrganizationId(organization.getId());
        policies.stream()
            .filter(not(OrganizationSecurityPolicy::isActive))
            .map(policy -> buildInactivePolicyAlert(organization, policy))
            .filter(Objects::nonNull)
            .forEach(alerts::add);

        policies.stream()
            .flatMap(policy -> policy.getRules().stream())
            .filter(rule -> rule.isActive() && rule.getRuleType() == SecurityRuleType.COMPLIANCE_CHECK)
            .map(rule -> buildRuleExpiryAlert(organization, rule))
            .filter(Objects::nonNull)
            .forEach(alerts::add);

        List<String> violations = fetchComplianceViolations(organization.getId());
        violations.stream()
            .map(message -> buildViolationAlert(organization, message))
            .filter(Objects::nonNull)
            .forEach(alerts::add);

        return alerts;
    }

    private ComplianceAlertDTO buildInactivePolicyAlert(Organization organization, OrganizationSecurityPolicy policy) {
        return ComplianceAlertDTO.builder()
            .id(generateStableId(organization.getId(), "policy-inactive-" + policy.getId()))
            .organizationId(organization.getId())
            .organizationName(organization.getName())
            .complianceType(policy.getName())
            .severity(SEVERITY_CRITICAL)
            .expiresOn(null)
            .notes("Policy is inactive and requires approval")
            .build();
    }

    private ComplianceAlertDTO buildRuleExpiryAlert(Organization organization, OrganizationSecurityRule rule) {
        LocalDate expiry = parseRuleDate(rule.getRuleValue());
        if (expiry == null) {
            return null;
        }

        String severity = expiry.isBefore(LocalDate.now().plusDays(ALERT_DAYS_CRITICAL)) ? SEVERITY_CRITICAL : SEVERITY_WARNING;

        return ComplianceAlertDTO.builder()
            .id(generateStableId(organization.getId(), "rule-expiry-" + rule.getId()))
            .organizationId(organization.getId())
            .organizationName(organization.getName())
            .complianceType(rule.getName())
            .severity(severity)
            .expiresOn(expiry)
            .notes("Compliance evidence expires on " + expiry)
            .build();
    }

    private ComplianceAlertDTO buildViolationAlert(Organization organization, String message) {
        String severity = message.toLowerCase(Locale.ENGLISH).contains("missing") ? SEVERITY_CRITICAL : SEVERITY_WARNING;
        String complianceType = resolveComplianceCategory(message);
        int daysUntilDue = severity.equals(SEVERITY_CRITICAL) ? ALERT_DAYS_CRITICAL : ALERT_DAYS_WARNING;

        return ComplianceAlertDTO.builder()
            .id(generateStableId(organization.getId(), "violation-" + message))
            .organizationId(organization.getId())
            .organizationName(organization.getName())
            .complianceType(complianceType)
            .severity(severity)
            .expiresOn(LocalDate.now().plusDays(daysUntilDue))
            .notes(message)
            .build();
    }

    private String resolveComplianceCategory(String message) {
        String lower = message.toLowerCase(Locale.ENGLISH);
        if (lower.contains("password")) {
            return "Password policy";
        }
        if (lower.contains("session")) {
            return "Session controls";
        }
        if (lower.contains("audit")) {
            return "Audit logging";
        }
        if (lower.contains("access")) {
            return "Access control";
        }
        return "Compliance review";
    }

    private LocalizationDefaultsDTO mapLocalizationDefaults(Organization organization) {
        Hospital referenceHospital = organization.getHospitals().stream().findFirst().orElse(null);

        String countryCode = referenceHospital != null && referenceHospital.getCountry() != null && !referenceHospital.getCountry().isBlank()
            ? normalizeRegionCode(referenceHospital.getCountry())
            : "US";
        String languageCode = "en";

        Locale locale = new Locale.Builder()
            .setLanguage(languageCode)
            .setRegion(countryCode)
            .build();
        String fallbackLocale = locale.toLanguageTag().replace('-', '_');
        String contactLanguage = locale.getDisplayLanguage(Locale.ENGLISH);
        String currencyCode = resolveCurrency(locale);

        return LocalizationDefaultsDTO.builder()
            .id(organization.getId())
            .organizationId(organization.getId())
            .organizationName(organization.getName())
            .fallbackLocale(fallbackLocale)
            .contactLanguage(contactLanguage)
            .currency(currencyCode)
            .build();
    }

    private String resolveCurrency(Locale locale) {
        try {
            return Currency.getInstance(locale).getCurrencyCode();
        } catch (IllegalArgumentException ex) {
            return "USD";
        }
    }

    private static Map<String, String> buildIso3ToIso2Cache() {
        Map<String, String> cache = new HashMap<>();
        for (String iso2 : Locale.getISOCountries()) {
            Locale locale = new Locale.Builder().setRegion(iso2).build();
            try {
                cache.put(locale.getISO3Country().toUpperCase(Locale.ENGLISH), iso2);
            } catch (MissingResourceException ignored) {
                // Ignore locales without ISO3 mapping
            }
        }
        return cache;
    }

    private String normalizeRegionCode(String rawRegion) {
        if (rawRegion == null) {
            return "US";
        }

        String trimmed = rawRegion.trim();
        if (trimmed.isEmpty()) {
            return "US";
        }

        String upper = trimmed.toUpperCase(Locale.ENGLISH);
        if (upper.length() == 2) {
            return upper;
        }

        if (upper.length() == 3) {
            String iso2 = ISO3_TO_ISO2_COUNTRY_CACHE.get(upper);
            if (iso2 != null) {
                return iso2;
            }
        }

        log.warn("Unsupported region code '{}' encountered; falling back to 'US'", rawRegion);
        return "US";
    }

    private ImplementationStatusDTO buildImplementationStatus(
        List<DirectoryEntryDTO> directory,
        List<ComplianceAlertDTO> alerts,
        List<LocalizationDefaultsDTO> localizationDefaults
    ) {
        boolean directoryReady = !directory.isEmpty();
        boolean creationWizardReady = directory.stream().allMatch(entry -> entry.getPrimaryContactEmail() != null);
        boolean localeProfilesReady = localizationDefaults.stream()
            .allMatch(defaults -> defaults.getFallbackLocale() != null && !defaults.getFallbackLocale().isBlank());
        boolean documentationReady = alerts.stream().noneMatch(alert -> SEVERITY_CRITICAL.equalsIgnoreCase(alert.getSeverity()));

        return ImplementationStatusDTO.builder()
            .directoryReady(directoryReady)
            .creationWizardReady(creationWizardReady)
            .brandLocaleProfilesReady(localeProfilesReady)
            .documentationReady(documentationReady)
            .build();
    }

    private String sanitizeIdentifier(String code, String fallbackName) {
        String source = code;
        if (source == null || source.isBlank()) {
            source = fallbackName;
        }
        if (source == null || source.isBlank()) {
            return "organization";
        }
        String normalized = source.trim().toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "-");
        if (normalized.isBlank()) {
            return "organization";
        }
        return normalized;
    }

    private UUID generateStableId(UUID organizationId, String salt) {
        String seed = organizationId + ":" + salt;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private LocalDate parseRuleDate(String ruleValue) {
        if (ruleValue == null || ruleValue.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(ruleValue.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
