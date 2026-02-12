package com.example.hms.service;

import com.example.hms.mapper.HospitalMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.enums.OrganizationType;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.HospitalWithDepartmentsDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.utility.RoleValidator;
import com.example.hms.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.MessageSource;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HospitalServiceImplTest {

    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private MessageSource messageSource;
    @Mock
    private RoleValidator roleValidator;

    private HospitalMapper hospitalMapper;
    private HospitalServiceImpl hospitalService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        hospitalMapper = new HospitalMapper();
        hospitalService = new HospitalServiceImpl(hospitalRepository, organizationRepository, hospitalMapper, messageSource, roleValidator);
    }

    @Test
    void getAllHospitals_shouldNormalizeFilters() {
        UUID organizationId = UUID.randomUUID();
        Hospital hospital = Hospital.builder()
                .name("Filter Hospital")
                .code("FIL-01")
                .active(true)
                .build();
        hospital.setId(UUID.randomUUID());

        when(hospitalRepository.findAllForFilters(any(), any(), any(), any()))
                .thenReturn(List.of(hospital));

        List<HospitalResponseDTO> results = hospitalService.getAllHospitals(
                organizationId, false, "  Ouaga  ", " Centre  ", Locale.ENGLISH);

        assertEquals(1, results.size());
        assertEquals("FIL-01", results.get(0).getCode());

        ArgumentCaptor<UUID> organizationCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Boolean> unassignedCaptor = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> cityCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);

        verify(hospitalRepository).findAllForFilters(organizationCaptor.capture(),
                unassignedCaptor.capture(), cityCaptor.capture(), stateCaptor.capture());

        assertEquals(organizationId, organizationCaptor.getValue());
        assertNull(unassignedCaptor.getValue());
        assertEquals("Ouaga", cityCaptor.getValue());
        assertEquals("Centre", stateCaptor.getValue());
    }

    @Test
    void getAllHospitals_unassignedOnlyIgnoresOrganization() {
        when(hospitalRepository.findAllForFilters(any(), any(), any(), any()))
                .thenReturn(List.of());

        hospitalService.getAllHospitals(UUID.randomUUID(), true, null, null, Locale.ENGLISH);

        verify(hospitalRepository).findAllForFilters(isNull(), eq(Boolean.TRUE), isNull(), isNull());
    }

    @Test
    void getHospitalsWithDepartments_shouldFilterDepartmentsByQuery() {
        Hospital hospital = Hospital.builder()
                .name("Mercy General")
                .code("MER-01")
                .active(true)
                .build();
        hospital.setId(UUID.randomUUID());

        Department cardiology = new Department();
        cardiology.setId(UUID.randomUUID());
        cardiology.setName("Cardiology");
        cardiology.setCode("CARD");
        cardiology.setEmail("card@mercy.example");
        cardiology.setPhoneNumber("555-1000");
        cardiology.setActive(true);
        cardiology.setBedCapacity(12);
        cardiology.setHospital(hospital);

        Staff head = new Staff();
        User headUser = new User();
        headUser.setFirstName("Amy");
        headUser.setLastName("Pond");
        headUser.setEmail("amy.pond@mercy.example");
        head.setUser(headUser);
        head.setName("Dr Amy Pond");
        cardiology.setHeadOfDepartment(head);
        cardiology.setStaffMembers(new HashSet<>(List.of(head)));

        Department orthopedics = new Department();
        orthopedics.setId(UUID.randomUUID());
        orthopedics.setName("Orthopedics");
        orthopedics.setCode("ORTH");
        orthopedics.setHospital(hospital);
        orthopedics.setStaffMembers(new HashSet<>());

        Set<Department> departments = new HashSet<>();
        departments.add(cardiology);
        departments.add(orthopedics);
        hospital.setDepartments(departments);

        when(hospitalRepository.findAllWithDepartments("mercy", true)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> results = hospitalService.getHospitalsWithDepartments(" mercy ", "card", true, Locale.ENGLISH);

        verify(hospitalRepository).findAllWithDepartments("mercy", true);
        assertEquals(1, results.size());
        HospitalWithDepartmentsDTO dto = results.get(0);
        assertEquals("Mercy General", dto.getHospitalName());
        assertEquals(1, dto.getDepartments().size());
        assertEquals("Cardiology", dto.getDepartments().get(0).getName());
        assertEquals(12, dto.getDepartments().get(0).getBedCount());
        assertEquals("amy.pond@mercy.example", dto.getDepartments().get(0).getHeadOfDepartmentEmail());
    }

    @Test
    void getHospitalsWithDepartments_shouldReturnEmptyWhenDepartmentQueryDoesNotMatch() {
        Hospital hospital = Hospital.builder()
                .name("City Hospital")
                .code("CITY-01")
                .active(true)
                .build();
        hospital.setId(UUID.randomUUID());

        Department pediatrics = new Department();
        pediatrics.setId(UUID.randomUUID());
        pediatrics.setName("Pediatrics");
        pediatrics.setCode("PED");
        pediatrics.setHospital(hospital);
        pediatrics.setStaffMembers(new HashSet<>());

        hospital.setDepartments(new HashSet<>(List.of(pediatrics)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> results = hospitalService.getHospitalsWithDepartments(null, "icu", null, Locale.ENGLISH);

        verify(hospitalRepository).findAllWithDepartments(null, null);
        assertTrue(results.isEmpty());
    }

    @Test
    void assignHospitalToOrganization_shouldAttachOrganization() {
        UUID hospitalId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        Hospital hospital = Hospital.builder()
                .name("Assign Hospital")
                .code("ASS-01")
                .active(true)
                .build();
        hospital.setId(hospitalId);

        Organization organization = Organization.builder()
                .name("Kouritenga Chain")
                .code("KPL")
                .type(OrganizationType.HOSPITAL_CHAIN)
                .active(true)
                .build();
        organization.setId(organizationId);

        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = hospitalService.assignHospitalToOrganization(hospitalId, organizationId, Locale.ENGLISH);

        assertEquals(organizationId, response.getOrganizationId());
        assertEquals("Kouritenga Chain", response.getOrganizationName());
        verify(hospitalRepository).save(hospital);
    }

    @Test
    void getHospitalsByOrganization_shouldReturnEmptyWhenOrganizationExistsButNoHospitals() {
        UUID organizationId = UUID.randomUUID();

        when(hospitalRepository.findByOrganizationIdOrderByNameAsc(organizationId)).thenReturn(List.of());
        when(organizationRepository.existsById(organizationId)).thenReturn(true);

        List<HospitalResponseDTO> result =
                hospitalService.getHospitalsByOrganization(organizationId, Locale.ENGLISH);

        assertTrue(result.isEmpty());
    }

    @Test
    void getHospitalsByOrganization_shouldThrowWhenOrganizationMissing() {
        UUID organizationId = UUID.randomUUID();

        when(hospitalRepository.findByOrganizationIdOrderByNameAsc(organizationId)).thenReturn(List.of());
        when(organizationRepository.existsById(organizationId)).thenReturn(false);
        when(messageSource.getMessage("organization.notFound", new Object[]{organizationId},
                "Organization not found with id: " + organizationId, Locale.ENGLISH))
                .thenReturn("Organization not found");

        assertThrows(ResourceNotFoundException.class,
                () -> hospitalService.getHospitalsByOrganization(organizationId, Locale.ENGLISH));
    }

    @Test
    void unassignHospitalFromOrganization_shouldClearLink() {
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = Hospital.builder()
                .name("Unassign Hospital")
                .code("UNA-01")
                .active(true)
                .build();
        hospital.setId(hospitalId);

        Organization organization = Organization.builder()
                .name("Chain")
                .code("CHN")
                .type(OrganizationType.HOSPITAL_CHAIN)
                .active(true)
                .build();
        organization.setId(UUID.randomUUID());
        hospital.setOrganization(organization);

        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = hospitalService.unassignHospitalFromOrganization(hospitalId, Locale.ENGLISH);

        assertNull(response.getOrganizationId());
        verify(hospitalRepository).save(hospital);
    }
}
