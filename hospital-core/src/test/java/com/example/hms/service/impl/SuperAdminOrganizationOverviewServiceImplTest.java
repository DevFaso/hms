package com.example.hms.service.impl;

import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.SecurityPolicyType;
import com.example.hms.enums.SecurityRuleType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.model.embedded.PlatformOwnership;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.StaffResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationHierarchyResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationsSummaryDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.service.OrganizationSecurityService;
import com.example.hms.service.PatientService;
import com.example.hms.service.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminOrganizationOverviewServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationSecurityPolicyRepository securityPolicyRepository;

    @Mock
    private OrganizationSecurityService organizationSecurityService;

    @Mock
    private StaffService staffService;

    @Mock
    private PatientService patientService;

    private SuperAdminOrganizationOverviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SuperAdminOrganizationOverviewServiceImpl(
            organizationRepository,
            securityPolicyRepository,
            organizationSecurityService,
            staffService,
            patientService
        );
    }

    @Test
    void getOrganizationsSummaryAggregatesDirectoryComplianceAndLocalization() {
        UUID organizationId = UUID.randomUUID();

        Organization organization = Organization.builder()
            .name("Northbridge Health")
            .code("NBH")
            .active(true)
            .build();
        organization.setId(organizationId);
        organization.setOwnership(PlatformOwnership.builder()
            .ownerContactEmail("security@northbridge.org")
            .dataSteward("Taylor Smith")
            .build());

        Hospital referenceHospital = Hospital.builder()
            .name("Northbridge General")
            .code("NBH-GEN")
            .country("CA")
            .build();
        referenceHospital.setId(UUID.randomUUID());
        organization.addHospital(referenceHospital);

        OrganizationSecurityPolicy inactivePolicy = OrganizationSecurityPolicy.builder()
            .name("SOC2 Policy")
            .code("SOC2")
            .active(false)
            .build();
        inactivePolicy.setId(UUID.randomUUID());
        inactivePolicy.setOrganization(organization);

        OrganizationSecurityPolicy activePolicy = OrganizationSecurityPolicy.builder()
            .name("HIPAA Evidence")
            .code("HIPAA-EV")
            .active(true)
            .build();
        activePolicy.setId(UUID.randomUUID());
        activePolicy.setOrganization(organization);

        OrganizationSecurityRule expiringRule = OrganizationSecurityRule.builder()
            .name("Quarterly HIPAA Evidence")
            .code("HIPAA-QUARTERLY")
            .ruleType(SecurityRuleType.COMPLIANCE_CHECK)
            .ruleValue(LocalDate.now().plusDays(5).toString())
            .active(true)
            .build();
        expiringRule.setId(UUID.randomUUID());
        activePolicy.addRule(expiringRule);

        when(organizationRepository.findAll()).thenReturn(List.of(organization));
        when(securityPolicyRepository.findByOrganizationId(organizationId))
            .thenReturn(List.of(inactivePolicy, activePolicy));
        when(organizationSecurityService.validateSecurityCompliance(organizationId))
            .thenReturn(List.of("Password rotation evidence missing for privileged accounts"));

        SuperAdminOrganizationsSummaryDTO summary = service.getOrganizationsSummary();

        assertThat(summary.getDirectory()).hasSize(1);
        SuperAdminOrganizationsSummaryDTO.DirectoryEntryDTO directoryEntry = summary.getDirectory().get(0);
        assertThat(directoryEntry.getId()).isEqualTo(organizationId);
        assertThat(directoryEntry.getName()).isEqualTo("Northbridge Health");
        assertThat(directoryEntry.getStatus()).isEqualTo("Active");
        assertThat(directoryEntry.getCompliancePosture()).isEqualTo("Action required");
        assertThat(directoryEntry.getPrimaryContactName()).isEqualTo("Taylor Smith");
        assertThat(directoryEntry.getPrimaryContactEmail()).isEqualTo("security@northbridge.org");

        assertThat(summary.getComplianceAlerts()).hasSize(3);
        assertThat(summary.getComplianceAlerts())
            .anyMatch(alert -> "SOC2 Policy".equals(alert.getComplianceType()) && "critical".equals(alert.getSeverity()))
            .anyMatch(alert -> "Quarterly HIPAA Evidence".equals(alert.getComplianceType()))
            .anyMatch(alert -> "Password policy".equals(alert.getComplianceType()));

        assertThat(summary.getLocalizationDefaults()).hasSize(1);
        SuperAdminOrganizationsSummaryDTO.LocalizationDefaultsDTO localizationDefaults = summary.getLocalizationDefaults().get(0);
        assertThat(localizationDefaults.getFallbackLocale()).isEqualTo("en_CA");
        assertThat(localizationDefaults.getCurrency()).isEqualTo("CAD");

        SuperAdminOrganizationsSummaryDTO.ImplementationStatusDTO implementationStatus = summary.getImplementationStatus();
        assertThat(implementationStatus.isDirectoryReady()).isTrue();
        assertThat(implementationStatus.isCreationWizardReady()).isTrue();
        assertThat(implementationStatus.isBrandLocaleProfilesReady()).isTrue();
        assertThat(implementationStatus.isDocumentationReady()).isFalse();
    }

    @Test
    void getOrganizationHierarchyRespectsLimitsAndAggregatesCounts() {
        UUID organizationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Organization organization = Organization.builder()
            .name("Aurora Health")
            .code("AUR")
            .active(true)
            .build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder()
            .name("Aurora Main")
            .code("AUR-MAIN")
            .city("Denver")
            .state("CO")
            .active(true)
            .build();
        hospital.setId(hospitalId);
        organization.addHospital(hospital);

        when(organizationRepository.findAll()).thenReturn(List.of(organization));

        StaffResponseDTO staff = StaffResponseDTO.builder()
            .id(UUID.randomUUID().toString())
            .name("Jordan Vega")
            .roleName("ROLE_DOCTOR")
            .jobTitle(JobTitle.DOCTOR)
            .email("jordan.vega@example.org")
            .phoneNumber("555-0100")
            .active(true)
            .build();
        Page<StaffResponseDTO> staffPage = new PageImpl<>(List.of(staff), PageRequest.of(0, 5), 5);
        when(staffService.getStaffByHospitalIdAndActiveTrue(eq(hospitalId), any(PageRequest.class))).thenReturn(staffPage);

        PatientResponseDTO patient = PatientResponseDTO.builder()
            .id(UUID.randomUUID())
            .firstName("Alex")
            .lastName("Lee")
            .mrn("MRN-4455")
            .email("alex.lee@example.org")
            .phoneNumberPrimary("555-0200")
            .active(true)
            .build();
        Page<PatientResponseDTO> patientPage = new PageImpl<>(List.of(patient), PageRequest.of(0, 5), 8);
        when(patientService.getPatientPageByHospital(eq(hospitalId), eq(true), any(PageRequest.class))).thenReturn(patientPage);

        SuperAdminOrganizationHierarchyResponseDTO hierarchy = service.getOrganizationHierarchy(
            true,
            true,
            true,
            null,
            5,
            5,
            Locale.US
        );

        assertThat(hierarchy.getOrganizations()).hasSize(1);
        SuperAdminOrganizationHierarchyResponseDTO.OrganizationHierarchyDTO node = hierarchy.getOrganizations().get(0);
        assertThat(node.getOrganizationId()).isEqualTo(organizationId);
        assertThat(node.getTotalHospitals()).isEqualTo(1);
        assertThat(node.getTotalStaff()).isEqualTo(5);
        assertThat(node.getTotalPatients()).isEqualTo(8);
        assertThat(node.getHospitals()).hasSize(1);

        SuperAdminOrganizationHierarchyResponseDTO.HospitalHierarchyDTO hospitalNode = node.getHospitals().get(0);
        assertThat(hospitalNode.getHospitalId()).isEqualTo(hospitalId);
        assertThat(hospitalNode.getStaffCount()).isEqualTo(5);
        assertThat(hospitalNode.getPatientCount()).isEqualTo(8);
        assertThat(hospitalNode.getStaff()).hasSize(1);
        assertThat(hospitalNode.getPatients()).hasSize(1);

        assertThat(hierarchy.getTotalStaff()).isEqualTo(5);
        assertThat(hierarchy.getTotalPatients()).isEqualTo(8);
        assertThat(hierarchy.isIncludeStaff()).isTrue();
        assertThat(hierarchy.isIncludePatients()).isTrue();
    }

    @Test
    void getOrganizationsSummaryHandlesMultipleAlertPaths() {
        UUID alphaId = UUID.randomUUID();
        Organization alpha = Organization.builder()
            .name("Alpha Health Network")
            .code("ALPHA")
            .description("Primary care")
            .type(OrganizationType.PRIVATE_PRACTICE)
            .active(true)
            .ownership(PlatformOwnership.builder()
                .dataSteward("Jane Doe")
                .ownerContactEmail("jane@alpha.org")
                .build())
            .build();
        alpha.setId(alphaId);
        alpha.setPrimaryContactEmail("   ");

        Hospital alphaHospital = Hospital.builder()
            .name("Alpha General")
            .code("AG-1")
            .city("Boston")
            .state("MA")
            .country("USA")
            .active(true)
            .build();
        alphaHospital.setId(UUID.randomUUID());
        alpha.addHospital(alphaHospital);

        OrganizationSecurityPolicy inactivePolicy = OrganizationSecurityPolicy.builder()
            .name("Password policy")
            .code("PASS")
            .policyType(SecurityPolicyType.ACCESS_CONTROL)
            .active(false)
            .build();
        inactivePolicy.setId(UUID.randomUUID());
        inactivePolicy.setOrganization(alpha);

        OrganizationSecurityPolicy evidencePolicy = OrganizationSecurityPolicy.builder()
            .name("Evidence tracking")
            .code("TRACK")
            .policyType(SecurityPolicyType.AUDIT_LOGGING)
            .active(true)
            .build();
        evidencePolicy.setId(UUID.randomUUID());
        evidencePolicy.setOrganization(alpha);

        OrganizationSecurityRule criticalRule = OrganizationSecurityRule.builder()
            .name("SOC2 evidence")
            .code("SOC2")
            .ruleType(SecurityRuleType.COMPLIANCE_CHECK)
            .ruleValue(LocalDate.now().plusDays(2).toString())
            .active(true)
            .build();
        criticalRule.setId(UUID.randomUUID());
        evidencePolicy.addRule(criticalRule);

        OrganizationSecurityRule warningRule = OrganizationSecurityRule.builder()
            .name("HIPAA documentation")
            .code("HIPAA")
            .ruleType(SecurityRuleType.COMPLIANCE_CHECK)
            .ruleValue(LocalDate.now().plusDays(30).toString())
            .active(true)
            .build();
        warningRule.setId(UUID.randomUUID());
        evidencePolicy.addRule(warningRule);

        UUID betaId = UUID.randomUUID();
        Organization beta = Organization.builder()
            .name("Beta Clinical Group")
            .code("BETA")
            .type(OrganizationType.GOVERNMENT_AGENCY)
            .active(false)
            .ownership(PlatformOwnership.builder()
                .ownerTeam("Operations East")
                .ownerContactEmail("")
                .build())
            .build();
        beta.setId(betaId);

        Hospital betaHospital = Hospital.builder()
            .name("Beta Specialty")
            .code("BS-9")
            .city("Chicago")
            .state("IL")
            .country("ZZZ")
            .active(true)
            .build();
        betaHospital.setId(UUID.randomUUID());
        beta.addHospital(betaHospital);

        when(organizationRepository.findAll()).thenReturn(List.of(alpha, beta));
        when(securityPolicyRepository.findByOrganizationId(alphaId)).thenReturn(List.of(inactivePolicy, evidencePolicy));
        when(securityPolicyRepository.findByOrganizationId(betaId)).thenReturn(List.of());
        when(organizationSecurityService.validateSecurityCompliance(alphaId)).thenReturn(List.of(
            "Password missing rotation policy",
            "Audit logs require review"
        ));
        when(organizationSecurityService.validateSecurityCompliance(betaId))
            .thenThrow(new IllegalStateException("compliance service unavailable"));

        SuperAdminOrganizationsSummaryDTO summary = service.getOrganizationsSummary();

        assertThat(summary.getDirectory()).hasSize(2);

        SuperAdminOrganizationsSummaryDTO.DirectoryEntryDTO alphaEntry = summary.getDirectory().stream()
            .filter(entry -> entry.getId().equals(alphaId))
            .findFirst()
            .orElseThrow();
        assertThat(alphaEntry.getPrimaryContactName()).isEqualTo("Jane Doe");
        assertThat(alphaEntry.getPrimaryContactEmail()).isEqualTo("jane@alpha.org");
        assertThat(alphaEntry.getCompliancePosture()).isEqualTo("Action required");

        SuperAdminOrganizationsSummaryDTO.DirectoryEntryDTO betaEntry = summary.getDirectory().stream()
            .filter(entry -> entry.getId().equals(betaId))
            .findFirst()
            .orElseThrow();
        assertThat(betaEntry.getPrimaryContactName()).isEqualTo("Operations East");
        assertThat(betaEntry.getPrimaryContactEmail()).isEqualTo("info@beta.example.org");
        assertThat(betaEntry.getCompliancePosture()).isEqualTo("Compliant");

        assertThat(summary.getComplianceAlerts()).hasSize(5);
        assertThat(summary.getComplianceAlerts())
            .filteredOn(alert -> alert.getOrganizationId().equals(alphaId))
            .extracting(SuperAdminOrganizationsSummaryDTO.ComplianceAlertDTO::getSeverity)
            .contains("critical", "warning");

        assertThat(summary.getLocalizationDefaults())
            .filteredOn(defaults -> defaults.getOrganizationId().equals(alphaId))
            .singleElement()
            .satisfies(defaults -> {
                assertThat(defaults.getFallbackLocale()).isEqualTo("en_US");
                assertThat(defaults.getCurrency()).isEqualTo("USD");
            });

        SuperAdminOrganizationsSummaryDTO.ImplementationStatusDTO status = summary.getImplementationStatus();
        assertThat(status.isDirectoryReady()).isTrue();
        assertThat(status.isCreationWizardReady()).isTrue();
        assertThat(status.isBrandLocaleProfilesReady()).isTrue();
        assertThat(status.isDocumentationReady()).isFalse();
    }

    @Test
    void getOrganizationHierarchyAppliesSearchAndActiveFilters() {
        UUID gammaId = UUID.randomUUID();
        Organization gamma = Organization.builder()
            .name("Gamma Health")
            .code("GAMMA")
            .active(true)
            .build();
        gamma.setId(gammaId);

        Hospital northCampus = Hospital.builder()
            .name("North Campus")
            .code("NC-1")
            .city("Denver")
            .state("CO")
            .country("US")
            .active(true)
            .build();
        northCampus.setId(UUID.randomUUID());
        gamma.addHospital(northCampus);

        Hospital legacyCampus = Hospital.builder()
            .name("Legacy Campus")
            .code("LC-1")
            .active(false)
            .build();
        legacyCampus.setId(UUID.randomUUID());
        gamma.addHospital(legacyCampus);

        UUID deltaId = UUID.randomUUID();
        Organization delta = Organization.builder()
            .name("Delta Network")
            .code("DELTA")
            .active(true)
            .build();
        delta.setId(deltaId);

        Hospital satelliteClinic = Hospital.builder()
            .name("Gamma Satellite Clinic")
            .code("GS-7")
            .city("Phoenix")
            .state("AZ")
            .country("CA")
            .active(true)
            .build();
        satelliteClinic.setId(UUID.randomUUID());
        delta.addHospital(satelliteClinic);

        when(organizationRepository.findAll()).thenReturn(List.of(gamma, delta));

        PageRequest staffRequest = PageRequest.of(0, 1);
        StaffResponseDTO staffMember = StaffResponseDTO.builder()
            .id("not-a-uuid")
            .username("nurse.gamma")
            .roleName("Nurse")
            .jobTitle(JobTitle.NURSE)
            .active(true)
            .build();
        when(staffService.getStaffByHospitalIdAndActiveTrue(northCampus.getId(), staffRequest))
            .thenReturn(new PageImpl<>(List.of(staffMember), staffRequest, 2));
        when(staffService.getStaffByHospitalIdAndActiveTrue(satelliteClinic.getId(), staffRequest))
            .thenReturn(new PageImpl<>(List.of(), staffRequest, 0));

        PageRequest patientRequest = PageRequest.of(0, 100);
        PatientResponseDTO patientA = PatientResponseDTO.builder()
            .id(UUID.randomUUID())
            .firstName("Alice")
            .lastName("Johnson")
            .mrn("MRN-1")
            .email("alice@example.org")
            .phoneNumberPrimary("555-1000")
            .active(true)
            .build();
        PatientResponseDTO patientB = PatientResponseDTO.builder()
            .id(UUID.randomUUID())
            .firstName("Bob")
            .mrn("MRN-2")
            .email("bob@example.org")
            .phoneNumberPrimary("555-2000")
            .active(true)
            .build();
        PatientResponseDTO patientC = PatientResponseDTO.builder()
            .id(UUID.randomUUID())
            .mrn("MRN-3")
            .email("unknown@example.org")
            .active(false)
            .build();
        when(patientService.getPatientPageByHospital(northCampus.getId(), Boolean.TRUE, patientRequest))
            .thenReturn(new PageImpl<>(List.of(patientA, patientB, patientC), patientRequest, 3));
        when(patientService.getPatientPageByHospital(satelliteClinic.getId(), Boolean.TRUE, patientRequest))
            .thenReturn(new PageImpl<>(List.of(), patientRequest, 0));

        SuperAdminOrganizationHierarchyResponseDTO response = service.getOrganizationHierarchy(
            true,
            true,
            true,
            "  GAM  ",
            0,
            150,
            Locale.ENGLISH
        );

        assertThat(response.getStaffLimit()).isEqualTo(1);
        assertThat(response.getPatientLimit()).isEqualTo(100);
        assertThat(response.getSearch()).isEqualTo("gam");
        assertThat(response.getTotalOrganizations()).isEqualTo(2);
        assertThat(response.getTotalHospitals()).isEqualTo(2);
        assertThat(response.getTotalStaff()).isEqualTo(2);
        assertThat(response.getTotalPatients()).isEqualTo(3);

        SuperAdminOrganizationHierarchyResponseDTO.OrganizationHierarchyDTO gammaNode = response.getOrganizations().stream()
            .filter(node -> node.getOrganizationId().equals(gammaId))
            .findFirst()
            .orElseThrow();
        assertThat(gammaNode.getHospitals()).hasSize(1);
        SuperAdminOrganizationHierarchyResponseDTO.HospitalHierarchyDTO gammaHospital = gammaNode.getHospitals().get(0);
        assertThat(gammaHospital.getStaffCount()).isEqualTo(2);
        assertThat(gammaHospital.getPatientCount()).isEqualTo(3);
        assertThat(gammaHospital.getStaff()).singleElement()
            .satisfies(summary -> {
                assertThat(summary.getFullName()).isEqualTo("nurse.gamma");
                assertThat(summary.getId()).isNull();
                assertThat(summary.getRole()).isEqualTo("Nurse");
            });
        assertThat(gammaHospital.getPatients())
            .extracting(SuperAdminOrganizationHierarchyResponseDTO.PatientSummaryDTO::getFullName)
            .containsExactly("Alice Johnson", "Bob", "Patient");

        SuperAdminOrganizationHierarchyResponseDTO.OrganizationHierarchyDTO deltaNode = response.getOrganizations().stream()
            .filter(node -> node.getOrganizationId().equals(deltaId))
            .findFirst()
            .orElseThrow();
        assertThat(deltaNode.getHospitals()).hasSize(1);
        assertThat(deltaNode.getHospitals().get(0).getHospitalName()).isEqualTo("Gamma Satellite Clinic");
        assertThat(deltaNode.getHospitals().get(0).getStaff()).isEmpty();
        assertThat(deltaNode.getHospitals().get(0).getPatients()).isEmpty();

        verify(patientService).getPatientPageByHospital(northCampus.getId(), Boolean.TRUE, patientRequest);
        verify(patientService).getPatientPageByHospital(satelliteClinic.getId(), Boolean.TRUE, patientRequest);
    }

    @Test
    void getOrganizationHierarchyReturnsEarlyWhenNoDirectoryDataRequested() {
        UUID orgId = UUID.randomUUID();
        Organization organization = Organization.builder()
            .name("Omicron Health")
            .code("OMICRON")
            .active(false)
            .build();
        organization.setId(orgId);

        Hospital inactiveHospital = Hospital.builder()
            .name("Omicron West")
            .code("OW-1")
            .active(false)
            .build();
        inactiveHospital.setId(UUID.randomUUID());
        organization.addHospital(inactiveHospital);

        when(organizationRepository.findAll()).thenReturn(List.of(organization));

        SuperAdminOrganizationHierarchyResponseDTO response = service.getOrganizationHierarchy(
            false,
            false,
            null,
            " ",
            5,
            5,
            Locale.CANADA
        );

        assertThat(response.getStaffLimit()).isEqualTo(5);
        assertThat(response.getPatientLimit()).isEqualTo(5);
        assertThat(response.getSearch()).isNull();
        assertThat(response.getOrganizations()).hasSize(1);
        SuperAdminOrganizationHierarchyResponseDTO.HospitalHierarchyDTO hospitalNode = response.getOrganizations().get(0).getHospitals().get(0);
        assertThat(hospitalNode.getStaff()).isEmpty();
        assertThat(hospitalNode.getPatients()).isEmpty();

        verifyNoInteractions(staffService, patientService);
    }
}
