package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminLabOrderCreateRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.LabOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminLabOrderServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private PatientHospitalRegistrationRepository registrationRepository;

    @Mock
    private StaffRepository staffRepository;

    @Mock
    private LabTestDefinitionRepository labTestDefinitionRepository;

    @Mock
    private LabOrderService labOrderService;

    @InjectMocks
    private SuperAdminLabOrderServiceImpl service;

    @Captor
    private ArgumentCaptor<LabOrderRequestDTO> requestCaptor;

    private Organization organization;
    private Hospital hospital;
    private PatientHospitalRegistration registration;
    private Staff staff;
    private LabTestDefinition testDefinition;

    @BeforeEach
    void setUp() {
        organization = Organization.builder()
            .code("CAREPLUS")
            .name("Care Plus Network")
            .active(true)
            .build();
        organization.setId(UUID.randomUUID());

        hospital = Hospital.builder()
            .code("RIVERSIDE")
            .name("Riverside General")
            .organization(organization)
            .active(true)
            .build();
        hospital.setId(UUID.randomUUID());

        Patient patient = Patient.builder()
            .firstName("Jane")
            .lastName("Doe")
            .email("jane.doe@example.com")
            .active(true)
            .build();
        patient.setId(UUID.randomUUID());

        registration = PatientHospitalRegistration.builder()
            .hospital(hospital)
            .patient(patient)
            .mrn("MRN-001")
            .active(true)
            .build();
        registration.setId(UUID.randomUUID());

        Role doctorRole = Role.builder()
            .code("ROLE_DOCTOR")
            .name("Doctor")
            .build();
        doctorRole.setId(UUID.randomUUID());

        User doctorUser = User.builder()
            .username("drsmith")
            .firstName("Alex")
            .lastName("Smith")
            .email("alex.smith@example.com")
            .build();
        doctorUser.setId(UUID.randomUUID());

        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .hospital(hospital)
            .role(doctorRole)
            .user(doctorUser)
            .active(true)
            .build();
        assignment.setId(UUID.randomUUID());

        staff = Staff.builder()
            .hospital(hospital)
            .user(doctorUser)
            .assignment(assignment)
            .active(true)
            .build();
        staff.setId(UUID.randomUUID());

        testDefinition = LabTestDefinition.builder()
            .hospital(hospital)
            .assignment(assignment)
            .testCode("CBC")
            .name("Complete Blood Count")
            .active(true)
            .build();
        testDefinition.setId(UUID.randomUUID());
    }

    @Test
    void createLabOrder_resolvesIdentifiersAndDelegatesToLabOrderService() {
        LocalDateTime orderTime = LocalDateTime.now();
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .orderDatetime(orderTime)
            .build();

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));

        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));

        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));

        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));

        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.of(testDefinition));

        LabOrderResponseDTO expectedResponse = LabOrderResponseDTO.builder()
            .id(UUID.randomUUID().toString())
            .status("PENDING")
            .build();
        when(labOrderService.createLabOrder(any(LabOrderRequestDTO.class), any(Locale.class)))
            .thenReturn(expectedResponse);

        LabOrderResponseDTO actual = service.createLabOrder(payload, Locale.ENGLISH);

        assertThat(actual).isEqualTo(expectedResponse);

        verify(labOrderService, times(1)).createLabOrder(requestCaptor.capture(), any(Locale.class));
        LabOrderRequestDTO request = requestCaptor.getValue();
        assertThat(request.getPatientId()).isEqualTo(registration.getPatient().getId());
        assertThat(request.getHospitalId()).isEqualTo(hospital.getId());
        assertThat(request.getOrderingStaffId()).isEqualTo(staff.getId());
        assertThat(request.getLabTestDefinitionId()).isEqualTo(testDefinition.getId());
        assertThat(request.getAssignmentId()).isEqualTo(staff.getAssignment().getId());
        assertThat(request.getStatus()).isEqualTo("PENDING");
        assertThat(request.getOrderDatetime()).isEqualTo(orderTime);
        assertThat(request.getTestName()).isEqualTo("Complete Blood Count");
        assertThat(request.getTestCode()).isEqualTo("CBC");
    }

    @Test
    void createLabOrder_fallsBackToGlobalDefinitionWhenHospitalScopedMissing() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .build();

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));

        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));

        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));

        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));

        LabTestDefinition globalDefinition = LabTestDefinition.builder()
            .testCode("CBC")
            .name("Complete Blood Count")
            .active(true)
            .build();
        globalDefinition.setId(UUID.randomUUID());

        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.empty());
        when(labTestDefinitionRepository.findActiveGlobalByIdentifier("CBC"))
            .thenReturn(Optional.of(globalDefinition));

        LabOrderResponseDTO expectedResponse = LabOrderResponseDTO.builder()
            .id(UUID.randomUUID().toString())
            .status("PENDING")
            .build();
        when(labOrderService.createLabOrder(any(LabOrderRequestDTO.class), any(Locale.class)))
            .thenReturn(expectedResponse);

        LabOrderResponseDTO actual = service.createLabOrder(payload, Locale.ENGLISH);

        assertThat(actual).isEqualTo(expectedResponse);
        verify(labOrderService, times(1)).createLabOrder(requestCaptor.capture(), any(Locale.class));
        LabOrderRequestDTO request = requestCaptor.getValue();
        assertThat(request.getLabTestDefinitionId()).isEqualTo(globalDefinition.getId());
        assertThat(request.getTestCode()).isEqualTo("CBC");
    }

    @Test
    void createLabOrder_ignoresDefinitionsFromOtherHospitalsDuringFallback() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .build();

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));

        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));

        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));

        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));

        Hospital otherHospital = Hospital.builder()
            .code("NORTHWING")
            .name("North Wing Clinic")
            .organization(organization)
            .active(true)
            .build();
        otherHospital.setId(UUID.randomUUID());

        LabTestDefinition otherHospitalDefinition = LabTestDefinition.builder()
            .hospital(otherHospital)
            .testCode("CBC")
            .name("Complete Blood Count")
            .active(true)
            .build();
        otherHospitalDefinition.setId(UUID.randomUUID());

        LabTestDefinition globalDefinition = LabTestDefinition.builder()
            .testCode("CBC")
            .name("Complete Blood Count")
            .active(true)
            .build();
        globalDefinition.setId(UUID.randomUUID());

        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.empty());
        when(labTestDefinitionRepository.findActiveGlobalByIdentifier("CBC"))
            .thenReturn(Optional.empty());
        when(labTestDefinitionRepository.findActiveByIdentifier("CBC"))
            .thenReturn(List.of(otherHospitalDefinition, globalDefinition));

        LabOrderResponseDTO expectedResponse = LabOrderResponseDTO.builder()
            .id(UUID.randomUUID().toString())
            .status("PENDING")
            .build();
        when(labOrderService.createLabOrder(any(LabOrderRequestDTO.class), any(Locale.class)))
            .thenReturn(expectedResponse);

        LabOrderResponseDTO actual = service.createLabOrder(payload, Locale.ENGLISH);

        assertThat(actual).isEqualTo(expectedResponse);
        verify(labOrderService).createLabOrder(requestCaptor.capture(), any(Locale.class));
        LabOrderRequestDTO request = requestCaptor.getValue();
        assertThat(request.getLabTestDefinitionId()).isEqualTo(globalDefinition.getId());
    }

    @Test
    void createLabOrder_missingOrganizationIdentifierThrowsBusinessException() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .organizationIdentifier(null)
            .build();

        assertThatThrownBy(() -> service.createLabOrder(payload, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("organizationIdentifier");
    }

    @Test
    void createLabOrder_resolvesOrganizationByNameWhenCodeLookupFails() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .organizationIdentifier("Care Plus Network")
            .build();

        when(organizationRepository.findByCode("CARE PLUS NETWORK"))
            .thenReturn(Optional.empty());
        when(organizationRepository.findByNameIgnoreCase("Care Plus Network"))
            .thenReturn(Optional.of(organization));

        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));
        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));
        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.of(testDefinition));

        LabOrderResponseDTO response = LabOrderResponseDTO.builder()
            .id(UUID.randomUUID().toString())
            .status("PENDING")
            .build();
        when(labOrderService.createLabOrder(any(LabOrderRequestDTO.class), any(Locale.class)))
            .thenReturn(response);

        LabOrderResponseDTO actual = service.createLabOrder(payload, Locale.ENGLISH);

        assertThat(actual).isEqualTo(response);
    }

    @Test
    void createLabOrder_hospitalOrganizationMismatchThrowsBusinessException() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .build();

        Organization otherOrganization = Organization.builder()
            .code("OTHERORG")
            .name("Other Org")
            .active(true)
            .build();
        otherOrganization.setId(UUID.randomUUID());

        Hospital mismatchedHospital = Hospital.builder()
            .code("RIVERSIDE")
            .name("Riverside General")
            .organization(otherOrganization)
            .active(true)
            .build();
        mismatchedHospital.setId(UUID.randomUUID());

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(mismatchedHospital));

        assertThatThrownBy(() -> service.createLabOrder(payload, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital does not belong to the specified organization");
    }

    @Test
    void createLabOrder_patientRegistrationNotFoundThrowsResourceNotFound() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .patientIdentifier("UNKNOWN")
            .build();

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "unknown"))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.createLabOrder(payload, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createLabOrder_multiplePatientMatchesThrowsBusinessException() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .build();

        PatientHospitalRegistration secondRegistration = PatientHospitalRegistration.builder()
            .hospital(hospital)
            .patient(registration.getPatient())
            .mrn("MRN-001")
            .active(true)
            .build();
        secondRegistration.setId(UUID.randomUUID());

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration, secondRegistration));

        assertThatThrownBy(() -> service.createLabOrder(payload, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Multiple patients match");
    }

    @Test
    void createLabOrder_staffLookupWithoutRoleUsesTrimAndDefaults() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .orderingStaffRole(null)
            .status("pending")
            .priority("  urgent  ")
            .notes("  follow up please  ")
            .build();

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));
        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), null, "drsmith"))
            .thenReturn(List.of(staff));
        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.of(testDefinition));

        LabOrderResponseDTO response = LabOrderResponseDTO.builder()
            .id(UUID.randomUUID().toString())
            .status("PENDING")
            .build();
        when(labOrderService.createLabOrder(any(LabOrderRequestDTO.class), any(Locale.class)))
            .thenReturn(response);

        LabOrderResponseDTO actual = service.createLabOrder(payload, Locale.ENGLISH);

        assertThat(actual).isEqualTo(response);
        verify(labOrderService).createLabOrder(requestCaptor.capture(), any(Locale.class));
        LabOrderRequestDTO request = requestCaptor.getValue();
        assertThat(request.getPriority()).isEqualTo("urgent");
        assertThat(request.getNotes()).isEqualTo("follow up please");
        assertThat(request.getTestResults()).isEmpty();
        assertThat(request.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void createLabOrder_staffWithoutAssignmentThrowsBusinessException() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .build();

        staff.setAssignment(null);

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));
        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));
        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.of(testDefinition));

        assertThatThrownBy(() -> service.createLabOrder(payload, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("valid hospital assignment");
    }

    @Test
    void createLabOrder_staffAssignmentMismatchThrowsBusinessException() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .build();

        Hospital otherHospital = Hospital.builder()
            .code("NORTHWING")
            .name("North Wing Clinic")
            .active(true)
            .build();
        otherHospital.setId(UUID.randomUUID());
        staff.getAssignment().setHospital(otherHospital);

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));
        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));
        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.of(testDefinition));

        assertThatThrownBy(() -> service.createLabOrder(payload, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not match the selected hospital");
    }

    @Test
    void createLabOrder_staffRoleMismatchThrowsBusinessException() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .orderingStaffRole("ROLE_NURSE")
            .build();

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));
        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_NURSE", "drsmith"))
            .thenReturn(List.of(staff));
        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.of(testDefinition));

        assertThatThrownBy(() -> service.createLabOrder(payload, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not hold the requested role");
    }

    @Test
    void createLabOrder_staffRoleMatchesByNameWhenCodeMissing() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .orderingStaffRole("Lead Doctor")
            .build();

        staff.getAssignment().getRole().setCode(null);
        staff.getAssignment().getRole().setName("Lead Doctor");

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));
        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "LEAD DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));
        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.of(testDefinition));

        LabOrderResponseDTO response = LabOrderResponseDTO.builder()
            .id(UUID.randomUUID().toString())
            .status("PENDING")
            .build();
        when(labOrderService.createLabOrder(any(LabOrderRequestDTO.class), any(Locale.class)))
            .thenReturn(response);

        LabOrderResponseDTO actual = service.createLabOrder(payload, Locale.ENGLISH);

        assertThat(actual).isEqualTo(response);
    }

    @Test
    void createLabOrder_labTestDefinitionResolvedByIdWhenUuidProvided() {
        UUID definitionId = UUID.randomUUID();
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .labTestIdentifier(definitionId.toString())
            .build();

        LabTestDefinition byIdDefinition = LabTestDefinition.builder()
            .hospital(hospital)
            .assignment(staff.getAssignment())
            .testCode("CBC")
            .name("Complete Blood Count")
            .active(true)
            .build();
        byIdDefinition.setId(definitionId);

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));
        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));
        when(labTestDefinitionRepository.findById(definitionId))
            .thenReturn(Optional.of(byIdDefinition));

        LabOrderResponseDTO response = LabOrderResponseDTO.builder()
            .id(UUID.randomUUID().toString())
            .status("PENDING")
            .build();
        when(labOrderService.createLabOrder(any(LabOrderRequestDTO.class), any(Locale.class)))
            .thenReturn(response);

        LabOrderResponseDTO actual = service.createLabOrder(payload, Locale.ENGLISH);

        assertThat(actual).isEqualTo(response);
        verify(labOrderService).createLabOrder(requestCaptor.capture(), any(Locale.class));
        LabOrderRequestDTO request = requestCaptor.getValue();
        assertThat(request.getLabTestDefinitionId()).isEqualTo(definitionId);
    }

    @Test
    void createLabOrder_labTestFallbackWithoutCandidatesThrowsResourceNotFound() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .build();

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));
        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));
        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.empty());
        when(labTestDefinitionRepository.findActiveGlobalByIdentifier("CBC"))
            .thenReturn(Optional.empty());
        when(labTestDefinitionRepository.findActiveByIdentifier("CBC"))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.createLabOrder(payload, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createLabOrder_labTestFallbackFiltersInactiveCandidatesBeforeThrowing() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .build();

        LabTestDefinition inactiveDefinition = LabTestDefinition.builder()
            .hospital(hospital)
            .assignment(staff.getAssignment())
            .testCode("CBC")
            .name("Complete Blood Count")
            .active(false)
            .build();
        inactiveDefinition.setId(UUID.randomUUID());

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));
        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));
        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.empty());
        when(labTestDefinitionRepository.findActiveGlobalByIdentifier("CBC"))
            .thenReturn(Optional.empty());
        when(labTestDefinitionRepository.findActiveByIdentifier("CBC"))
            .thenReturn(List.of(inactiveDefinition));

        assertThatThrownBy(() -> service.createLabOrder(payload, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createLabOrder_labTestFallbackPrefersHospitalSpecificDefinitionWhenAvailable() {
        SuperAdminLabOrderCreateRequestDTO payload = basePayloadBuilder()
            .build();

        LabTestDefinition hospitalScoped = LabTestDefinition.builder()
            .hospital(hospital)
            .assignment(staff.getAssignment())
            .testCode("CBC")
            .name("Complete Blood Count")
            .active(true)
            .build();
        hospitalScoped.setUpdatedAt(LocalDateTime.now());
        hospitalScoped.setId(UUID.randomUUID());

        LabTestDefinition globalDefinition = LabTestDefinition.builder()
            .testCode("CBC")
            .name("Complete Blood Count")
            .active(true)
            .build();
        globalDefinition.setUpdatedAt(LocalDateTime.now().minusDays(1));
        globalDefinition.setId(UUID.randomUUID());

        when(organizationRepository.findByCode("CAREPLUS"))
            .thenReturn(Optional.of(organization));
        when(hospitalRepository.findByCodeIgnoreCase("RIVERSIDE"))
            .thenReturn(Optional.of(hospital));
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "mrn-001"))
            .thenReturn(List.of(registration));
        when(staffRepository.findActiveByHospitalAndRoleAndIdentifier(hospital.getId(), "ROLE_DOCTOR", "drsmith"))
            .thenReturn(List.of(staff));
        when(labTestDefinitionRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "CBC"))
            .thenReturn(Optional.empty());
        when(labTestDefinitionRepository.findActiveGlobalByIdentifier("CBC"))
            .thenReturn(Optional.empty());
        when(labTestDefinitionRepository.findActiveByIdentifier("CBC"))
            .thenReturn(List.of(globalDefinition, hospitalScoped));

        LabOrderResponseDTO response = LabOrderResponseDTO.builder()
            .id(UUID.randomUUID().toString())
            .status("PENDING")
            .build();
        when(labOrderService.createLabOrder(any(LabOrderRequestDTO.class), any(Locale.class)))
            .thenReturn(response);

        LabOrderResponseDTO actual = service.createLabOrder(payload, Locale.ENGLISH);

        assertThat(actual).isEqualTo(response);
        verify(labOrderService).createLabOrder(requestCaptor.capture(), any(Locale.class));
        assertThat(requestCaptor.getValue().getLabTestDefinitionId()).isEqualTo(hospitalScoped.getId());
    }

    private SuperAdminLabOrderCreateRequestDTO.SuperAdminLabOrderCreateRequestDTOBuilder basePayloadBuilder() {
        return SuperAdminLabOrderCreateRequestDTO.builder()
            .organizationIdentifier("CAREPLUS")
            .hospitalIdentifier("RIVERSIDE")
            .patientIdentifier("MRN-001")
            .orderingStaffIdentifier("drsmith")
            .orderingStaffRole("ROLE_DOCTOR")
            .labTestIdentifier("CBC")
            .status("PENDING")
            .orderDatetime(LocalDateTime.now())
            .priority("ROUTINE")
            .notes("Collect before noon")
            .clinicalIndication("Rule out anemia")
            .medicalNecessityNote("Needed for diagnosis")
            .primaryDiagnosisCode("A01.1")
            .additionalDiagnosisCodes(List.of("B20"))
            .orderChannel("ELECTRONIC")
            .orderChannelOther(null)
            .documentationSharedWithLab(true)
            .documentationReference("DOC-123")
            .orderingProviderNpi("1234567890")
            .providerSignature("signed-by-super-admin")
            .signedAt(LocalDateTime.now())
            .testResults(List.of())
            .standingOrder(false);
    }
}
