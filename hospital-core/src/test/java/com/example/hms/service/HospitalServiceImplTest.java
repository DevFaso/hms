package com.example.hms.service;

import com.example.hms.mapper.HospitalMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.enums.OrganizationType;
import com.example.hms.payload.dto.HospitalRequestDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.HospitalWithDepartmentsDTO;
import com.example.hms.payload.dto.DepartmentSummaryDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.utility.RoleValidator;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.apache.kafka.common.errors.DuplicateResourceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
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

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    // ─── helpers ───

    private Hospital buildHospital(UUID id, String name, String code) {
        Hospital h = Hospital.builder().name(name).code(code).active(true).build();
        h.setId(id);
        return h;
    }

    private Organization buildOrganization(UUID id, String name, String code) {
        Organization o = Organization.builder()
                .name(name).code(code).type(OrganizationType.HOSPITAL_CHAIN).active(true).build();
        o.setId(id);
        return o;
    }

    private HospitalRequestDTO buildUSRequest(String name) {
        return HospitalRequestDTO.builder()
                .name(name).address("123 Main").city("NY").state("New York")
                .zipCode("10001").country("US").phoneNumber("1234567890").email("h@example.com")
                .active(true).build();
    }

    private void stubMessage(String key) {
        lenient().when(messageSource.getMessage(eq(key), any(), anyString(), any(Locale.class)))
                .thenReturn("[" + key + "]");
        lenient().when(messageSource.getMessage(eq(key), any(), any(Locale.class)))
                .thenReturn("[" + key + "]");
    }

    private void setSuperAdmin() {
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
    }

    private void setSuperAdminContext() {
        HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(UUID.randomUUID())
                .superAdmin(true)
                .build());
    }

    private void setScopedContext(UUID... hospitalIds) {
        HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(UUID.randomUUID())
                .principalUsername("user")
                .superAdmin(false)
                .permittedHospitalIds(Set.of(hospitalIds))
                .build());
    }

    // ═══════════════ getAllHospitals ═══════════════

    @Test
    void getAllHospitals_shouldNormalizeFilters() {
        UUID organizationId = UUID.randomUUID();
        Hospital hospital = buildHospital(UUID.randomUUID(), "Filter Hospital", "FIL-01");

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
    @DisplayName("getAllHospitals normalizes empty strings to null")
    void getAllHospitals_emptyStringsNormalized() {
        when(hospitalRepository.findAllForFilters(any(), any(), any(), any()))
                .thenReturn(List.of());

        hospitalService.getAllHospitals(null, null, "  ", "", Locale.ENGLISH);

        verify(hospitalRepository).findAllForFilters(isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("getAllHospitals applies hospital scope filtering for scoped user")
    void getAllHospitals_scopeFiltering() {
        UUID scopedId = UUID.randomUUID();
        UUID unscopedId = UUID.randomUUID();
        setScopedContext(scopedId);

        Hospital h1 = buildHospital(scopedId, "Scoped", "S-01");
        Hospital h2 = buildHospital(unscopedId, "Unscoped", "U-01");

        when(hospitalRepository.findAllForFilters(any(), any(), any(), any()))
                .thenReturn(List.of(h1, h2));

        List<HospitalResponseDTO> result = hospitalService.getAllHospitals(null, null, null, null, Locale.ENGLISH);

        assertEquals(1, result.size());
        assertEquals("Scoped", result.get(0).getName());
    }

    @Test
    @DisplayName("getAllHospitals returns all for superAdmin context")
    void getAllHospitals_superAdminReturnsAll() {
        setSuperAdminContext();

        Hospital h1 = buildHospital(UUID.randomUUID(), "H1", "H-01");
        Hospital h2 = buildHospital(UUID.randomUUID(), "H2", "H-02");

        when(hospitalRepository.findAllForFilters(any(), any(), any(), any()))
                .thenReturn(List.of(h1, h2));

        List<HospitalResponseDTO> result = hospitalService.getAllHospitals(null, null, null, null, Locale.ENGLISH);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("getAllHospitals returns all for anonymous context")
    void getAllHospitals_anonymousReturnsAll() {
        // anonymous = principalUserId == null && principalUsername == null
        HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(null).principalUsername(null).superAdmin(false).build());

        Hospital h1 = buildHospital(UUID.randomUUID(), "H1", "H-01");
        when(hospitalRepository.findAllForFilters(any(), any(), any(), any()))
                .thenReturn(List.of(h1));

        List<HospitalResponseDTO> result = hospitalService.getAllHospitals(null, null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("getAllHospitals returns empty when scoped user has empty scope")
    void getAllHospitals_emptyScopeReturnsEmpty() {
        HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(UUID.randomUUID()).principalUsername("user")
                .superAdmin(false).permittedHospitalIds(Set.of()).build());

        Hospital h1 = buildHospital(UUID.randomUUID(), "H1", "H-01");
        when(hospitalRepository.findAllForFilters(any(), any(), any(), any()))
                .thenReturn(List.of(h1));

        List<HospitalResponseDTO> result = hospitalService.getAllHospitals(null, null, null, null, Locale.ENGLISH);
        assertTrue(result.isEmpty());
    }

    // ═══════════════ getHospitalById ═══════════════

    @Nested
    @DisplayName("getHospitalById")
    class GetHospitalById {

        @Test
        @DisplayName("returns DTO when found and user has access")
        void found() {
            UUID id = UUID.randomUUID();
            setSuperAdminContext();
            Hospital hospital = buildHospital(id, "Test Hospital", "TST-01");

            when(hospitalRepository.findById(id)).thenReturn(Optional.of(hospital));

            HospitalResponseDTO result = hospitalService.getHospitalById(id, Locale.ENGLISH);
            assertEquals("Test Hospital", result.getName());
        }

        @Test
        @DisplayName("throws when not found")
        void notFound() {
            UUID id = UUID.randomUUID();
            stubMessage("hospital.notFound");
            when(hospitalRepository.findById(id)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> hospitalService.getHospitalById(id, Locale.ENGLISH));
        }

        @Test
        @DisplayName("throws AccessDenied when user has no scope access")
        void noScopeAccess() {
            UUID hospitalId = UUID.randomUUID();
            setScopedContext(UUID.randomUUID()); // scope doesn't include hospitalId

            Hospital hospital = buildHospital(hospitalId, "Restricted", "RES-01");
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            stubMessage("access.denied");

            assertThrows(AccessDeniedException.class,
                    () -> hospitalService.getHospitalById(hospitalId, Locale.ENGLISH));
        }
    }

    // ═══════════════ createHospital ═══════════════

    @Nested
    @DisplayName("createHospital")
    class CreateHospital {

        @Test
        @DisplayName("creates hospital successfully for US address")
        void usAddress() {
            setSuperAdmin();
            HospitalRequestDTO dto = buildUSRequest("New Hospital");

            when(hospitalRepository.existsByNameIgnoreCaseAndZipCode("New Hospital", "10001")).thenReturn(false);
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> {
                Hospital h = inv.getArgument(0);
                h.setId(UUID.randomUUID());
                return h;
            });

            HospitalResponseDTO result = hospitalService.createHospital(dto, Locale.ENGLISH);
            assertNotNull(result);
            verify(hospitalRepository).save(any(Hospital.class));
        }

        @Test
        @DisplayName("creates hospital with organization")
        void withOrganization() {
            setSuperAdmin();
            UUID orgId = UUID.randomUUID();
            HospitalRequestDTO dto = buildUSRequest("Org Hospital");
            dto.setOrganizationId(orgId);

            Organization org = buildOrganization(orgId, "Chain", "CHN");

            when(hospitalRepository.existsByNameIgnoreCaseAndZipCode("Org Hospital", "10001")).thenReturn(false);
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> {
                Hospital h = inv.getArgument(0);
                h.setId(UUID.randomUUID());
                return h;
            });

            HospitalResponseDTO result = hospitalService.createHospital(dto, Locale.ENGLISH);
            assertNotNull(result);
        }

        @Test
        @DisplayName("throws DuplicateResourceException when hospital already exists")
        void duplicate() {
            setSuperAdmin();
            HospitalRequestDTO dto = buildUSRequest("Existing Hospital");
            stubMessage("hospital.exists");

            when(hospitalRepository.existsByNameIgnoreCaseAndZipCode("Existing Hospital", "10001")).thenReturn(true);

            assertThrows(DuplicateResourceException.class,
                    () -> hospitalService.createHospital(dto, Locale.ENGLISH));
        }

        @Test
        @DisplayName("throws AccessDeniedException when not super admin")
        void notSuperAdmin() {
            when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
            stubMessage("access.denied");
            HospitalRequestDTO request = buildUSRequest("Any");

            assertThrows(AccessDeniedException.class,
                    () -> hospitalService.createHospital(request, Locale.ENGLISH));
        }

        @Test
        @DisplayName("throws when organization not found")
        void orgNotFound() {
            setSuperAdmin();
            UUID orgId = UUID.randomUUID();
            HospitalRequestDTO dto = buildUSRequest("Hospital");
            dto.setOrganizationId(orgId);
            stubMessage("organization.notFound");

            when(hospitalRepository.existsByNameIgnoreCaseAndZipCode(any(), any())).thenReturn(false);
            when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> hospitalService.createHospital(dto, Locale.ENGLISH));
        }
    }

    // ═══════════════ updateHospital ═══════════════

    @Nested
    @DisplayName("updateHospital")
    class UpdateHospital {

        @Test
        @DisplayName("updates hospital successfully")
        void happyPath() {
            setSuperAdmin();
            UUID id = UUID.randomUUID();
            Hospital hospital = buildHospital(id, "Old Name", "OLD-01");
            HospitalRequestDTO dto = buildUSRequest("New Name");

            when(hospitalRepository.findById(id)).thenReturn(Optional.of(hospital));
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

            HospitalResponseDTO result = hospitalService.updateHospital(id, dto, Locale.ENGLISH);
            assertNotNull(result);
            verify(hospitalRepository).save(hospital);
        }

        @Test
        @DisplayName("updates hospital with new organization")
        void withOrganization() {
            setSuperAdmin();
            UUID id = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();
            Hospital hospital = buildHospital(id, "Hospital", "HOS-01");
            HospitalRequestDTO dto = buildUSRequest("Hospital");
            dto.setOrganizationId(orgId);

            Organization org = buildOrganization(orgId, "Chain", "CHN");

            when(hospitalRepository.findById(id)).thenReturn(Optional.of(hospital));
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

            HospitalResponseDTO result = hospitalService.updateHospital(id, dto, Locale.ENGLISH);
            assertNotNull(result);
        }

        @Test
        @DisplayName("throws when hospital not found")
        void notFound() {
            setSuperAdmin();
            UUID id = UUID.randomUUID();
            stubMessage("hospital.notFound");
            when(hospitalRepository.findById(id)).thenReturn(Optional.empty());
            HospitalRequestDTO request = buildUSRequest("Any");

            assertThrows(ResourceNotFoundException.class,
                    () -> hospitalService.updateHospital(id, request, Locale.ENGLISH));
        }
    }

    // ═══════════════ deleteHospital ═══════════════

    @Nested
    @DisplayName("deleteHospital")
    class DeleteHospital {

        @Test
        @DisplayName("deletes existing hospital")
        void happyPath() {
            setSuperAdmin();
            UUID id = UUID.randomUUID();
            when(hospitalRepository.existsById(id)).thenReturn(true);

            hospitalService.deleteHospital(id, Locale.ENGLISH);

            verify(hospitalRepository).deleteById(id);
        }

        @Test
        @DisplayName("throws when hospital not found")
        void notFound() {
            setSuperAdmin();
            UUID id = UUID.randomUUID();
            stubMessage("hospital.notFound");
            when(hospitalRepository.existsById(id)).thenReturn(false);

            assertThrows(ResourceNotFoundException.class,
                    () -> hospitalService.deleteHospital(id, Locale.ENGLISH));
        }

        @Test
        @DisplayName("throws when not super admin")
        void notSuperAdmin() {
            when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
            stubMessage("access.denied");
            UUID id = UUID.randomUUID();

            assertThrows(AccessDeniedException.class,
                    () -> hospitalService.deleteHospital(id, Locale.ENGLISH));
        }
    }

    // ═══════════════ searchHospitals ═══════════════

    @Test
    @DisplayName("searchHospitals delegates to repository")
    void searchHospitals() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Search Result", "SR-01");
        Page<Hospital> page = new PageImpl<>(List.of(hospital));

        when(hospitalRepository.searchHospitals(eq("search"), eq("city"), eq("state"), eq(true), any(Pageable.class)))
                .thenReturn(page);

        List<HospitalResponseDTO> result = hospitalService.searchHospitals("search", "city", "state", true, 0, 10, Locale.ENGLISH);

        assertEquals(1, result.size());
        assertEquals("Search Result", result.get(0).getName());
    }

    // ═══════════════ getHospitalsWithDepartments ═══════════════

    @Test
    void getHospitalsWithDepartments_shouldFilterDepartmentsByQuery() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Mercy General", "MER-01");

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
        Hospital hospital = buildHospital(UUID.randomUUID(), "City Hospital", "CITY-01");

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
    @DisplayName("getHospitalsWithDepartments returns empty when repository returns empty")
    void getHospitalsWithDepartments_emptyRepo() {
        when(hospitalRepository.findAllWithDepartments(any(), any())).thenReturn(List.of());

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getHospitalsWithDepartments with no department query includes all departments")
    void getHospitalsWithDepartments_noDeptQuery() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital A", "HA-01");

        Department d1 = new Department();
        d1.setId(UUID.randomUUID());
        d1.setName("Dept 1");
        d1.setCode("D1");
        d1.setHospital(hospital);
        d1.setStaffMembers(new HashSet<>());

        Department d2 = new Department();
        d2.setId(UUID.randomUUID());
        d2.setName("Dept 2");
        d2.setCode("D2");
        d2.setHospital(hospital);
        d2.setStaffMembers(new HashSet<>());

        hospital.setDepartments(new HashSet<>(List.of(d1, d2)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getDepartments().size());
    }

    @Test
    @DisplayName("getHospitalsWithDepartments matches by head of department user email")
    void getHospitalsWithDepartments_matchByHeadEmail() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital B", "HB-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Neurology");
        dept.setCode("NEU");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        Staff head = new Staff();
        User headUser = new User();
        headUser.setEmail("drjones@hospital.com");
        headUser.setFirstName("Indiana");
        headUser.setLastName("Jones");
        head.setUser(headUser);
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "drjones", null, Locale.ENGLISH);
        assertEquals(1, result.size());
        assertEquals("Neurology", result.get(0).getDepartments().get(0).getName());
    }

    @Test
    @DisplayName("getHospitalsWithDepartments matches by hospital name in department search")
    void getHospitalsWithDepartments_matchByHospitalName() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Mayo Clinic", "MAYO-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Surgery");
        dept.setCode("SUR");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "mayo", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("getHospitalsWithDepartments with null departments set")
    void getHospitalsWithDepartments_nullDepartments() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Empty Hospital", "EMP-01");
        hospital.setDepartments(null);

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
        assertTrue(result.get(0).getDepartments().isEmpty());
    }

    @Test
    @DisplayName("getHospitalsWithDepartments with department head having no user")
    void getHospitalsWithDepartments_headNoUser() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital C", "HC-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Radiology");
        dept.setCode("RAD");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        Staff head = new Staff();
        head.setUser(null);
        head.setName("Dr. NoUser");
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
        assertEquals("Dr. NoUser", result.get(0).getDepartments().get(0).getHeadOfDepartmentName());
        assertNull(result.get(0).getDepartments().get(0).getHeadOfDepartmentEmail());
    }

    @Test
    @DisplayName("getHospitalsWithDepartments with head user having only firstName")
    void getHospitalsWithDepartments_headUserFirstNameOnly() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital D", "HD-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Oncology");
        dept.setCode("ONC");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        Staff head = new Staff();
        User headUser = new User();
        headUser.setFirstName("Alice");
        headUser.setLastName(null);
        headUser.setEmail("alice@hospital.com");
        head.setUser(headUser);
        head.setName(null);
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals("Alice", result.get(0).getDepartments().get(0).getHeadOfDepartmentName());
    }

    @Test
    @DisplayName("getHospitalsWithDepartments with head user blank full name falls back to staff name")
    void getHospitalsWithDepartments_headUserBlankNameFallback() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital E", "HE-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Pathology");
        dept.setCode("PATH");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        Staff head = new Staff();
        User headUser = new User();
        headUser.setFirstName("  ");
        headUser.setLastName("  ");
        headUser.setEmail("blank@hospital.com");
        head.setUser(headUser);
        head.setName("Dr. Staff Name");
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals("Dr. Staff Name", result.get(0).getDepartments().get(0).getHeadOfDepartmentName());
    }

    @Test
    @DisplayName("getHospitalsWithDepartments match by head staff name")
    void getHospitalsWithDepartments_matchByHeadStaffName() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital F", "HF-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dermatology");
        dept.setCode("DERM");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        Staff head = new Staff();
        head.setName("Dr. Matcher");
        head.setUser(null);
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "matcher", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("getHospitalsWithDepartments match by head user full name")
    void getHospitalsWithDepartments_matchByHeadUserFullName() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital G", "HG-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Gastroenterology");
        dept.setCode("GAST");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        Staff head = new Staff();
        User headUser = new User();
        headUser.setFirstName("John");
        headUser.setLastName("Doe");
        headUser.setEmail("jd@hospital.com");
        head.setUser(headUser);
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "john doe", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    // ═══════════════ assignHospitalToOrganization ═══════════════

    @Test
    void assignHospitalToOrganization_shouldAttachOrganization() {
        UUID hospitalId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        Hospital hospital = buildHospital(hospitalId, "Assign Hospital", "ASS-01");
        Organization organization = buildOrganization(organizationId, "Kouritenga Chain", "KPL");

        setSuperAdmin();
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = hospitalService.assignHospitalToOrganization(hospitalId, organizationId, Locale.ENGLISH);

        assertEquals(organizationId, response.getOrganizationId());
        assertEquals("Kouritenga Chain", response.getOrganizationName());
        verify(hospitalRepository).save(hospital);
    }

    // ═══════════════ getHospitalsByOrganization ═══════════════

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
        stubMessage("organization.notFound");

        assertThrows(ResourceNotFoundException.class,
                () -> hospitalService.getHospitalsByOrganization(organizationId, Locale.ENGLISH));
    }

    @Test
    @DisplayName("getHospitalsByOrganization returns hospitals with scope applied")
    void getHospitalsByOrganization_withScope() {
        UUID organizationId = UUID.randomUUID();
        UUID scopedId = UUID.randomUUID();
        setScopedContext(scopedId);

        Hospital h1 = buildHospital(scopedId, "Scoped", "S-01");
        Hospital h2 = buildHospital(UUID.randomUUID(), "Unscoped", "U-01");

        when(hospitalRepository.findByOrganizationIdOrderByNameAsc(organizationId)).thenReturn(List.of(h1, h2));

        List<HospitalResponseDTO> result = hospitalService.getHospitalsByOrganization(organizationId, Locale.ENGLISH);
        assertEquals(1, result.size());
        assertEquals("Scoped", result.get(0).getName());
    }

    // ═══════════════ unassignHospitalFromOrganization ═══════════════

    @Test
    void unassignHospitalFromOrganization_shouldClearLink() {
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = buildHospital(hospitalId, "Unassign Hospital", "UNA-01");
        Organization organization = buildOrganization(UUID.randomUUID(), "Chain", "CHN");
        hospital.setOrganization(organization);

        setSuperAdmin();
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = hospitalService.unassignHospitalFromOrganization(hospitalId, Locale.ENGLISH);

        assertNull(response.getOrganizationId());
        verify(hospitalRepository).save(hospital);
    }

    // ═══════════════ Address Validation ═══════════════

    @Nested
    @DisplayName("Address validation")
    class AddressValidation {

        @Test
        @DisplayName("US address requires state")
        void usNoState() {
            setSuperAdmin();
            HospitalRequestDTO dto = HospitalRequestDTO.builder()
                    .name("No State").city("NY").country("US")
                    .zipCode("10001").phoneNumber("1234567890")
                    .active(true).build();
            stubMessage("hospital.state.required");

            assertThrows(IllegalArgumentException.class,
                    () -> hospitalService.createHospital(dto, Locale.ENGLISH));
        }

        @Test
        @DisplayName("US address requires zipCode")
        void usNoZip() {
            setSuperAdmin();
            HospitalRequestDTO dto = HospitalRequestDTO.builder()
                    .name("No Zip").city("NY").country("US").state("NY")
                    .phoneNumber("1234567890")
                    .active(true).build();
            stubMessage("hospital.zipCode.required");

            assertThrows(IllegalArgumentException.class,
                    () -> hospitalService.createHospital(dto, Locale.ENGLISH));
        }

        @Test
        @DisplayName("USA country code treated same as US")
        void usaCode() {
            setSuperAdmin();
            HospitalRequestDTO dto = HospitalRequestDTO.builder()
                    .name("USA Hospital").city("LA").country("USA")
                    .state("CA").zipCode("90001").phoneNumber("1234567890")
                    .active(true).build();

            when(hospitalRepository.existsByNameIgnoreCaseAndZipCode(any(), any())).thenReturn(false);
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> {
                Hospital h = inv.getArgument(0);
                h.setId(UUID.randomUUID());
                return h;
            });

            assertNotNull(hospitalService.createHospital(dto, Locale.ENGLISH));
        }

        @Test
        @DisplayName("US address with blank state throws")
        void usBlankState() {
            setSuperAdmin();
            HospitalRequestDTO dto = HospitalRequestDTO.builder()
                    .name("Blank State").city("NY").country("US").state("  ")
                    .zipCode("10001").phoneNumber("1234567890")
                    .active(true).build();
            stubMessage("hospital.state.required");

            assertThrows(IllegalArgumentException.class,
                    () -> hospitalService.createHospital(dto, Locale.ENGLISH));
        }

        @Test
        @DisplayName("US address with blank zipCode throws")
        void usBlankZip() {
            setSuperAdmin();
            HospitalRequestDTO dto = HospitalRequestDTO.builder()
                    .name("Blank Zip").city("NY").country("US").state("NY")
                    .zipCode("  ").phoneNumber("1234567890")
                    .active(true).build();
            stubMessage("hospital.zipCode.required");

            assertThrows(IllegalArgumentException.class,
                    () -> hospitalService.createHospital(dto, Locale.ENGLISH));
        }

        @Test
        @DisplayName("foreign country requires address or poBox")
        void foreignNoAddress() {
            setSuperAdmin();
            HospitalRequestDTO dto = HospitalRequestDTO.builder()
                    .name("Foreign").city("Paris").country("FR")
                    .phoneNumber("1234567890").active(true).build();
            stubMessage("hospital.address.required");

            assertThrows(IllegalArgumentException.class,
                    () -> hospitalService.createHospital(dto, Locale.ENGLISH));
        }

        @Test
        @DisplayName("foreign country with poBox is valid")
        void foreignWithPoBox() {
            setSuperAdmin();
            HospitalRequestDTO dto = HospitalRequestDTO.builder()
                    .name("Foreign PO").city("Ouaga").country("BF")
                    .poBox("BP 123").phoneNumber("1234567890").active(true).build();

            when(hospitalRepository.existsByNameIgnoreCaseAndZipCode(any(), any())).thenReturn(false);
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> {
                Hospital h = inv.getArgument(0);
                h.setId(UUID.randomUUID());
                return h;
            });

            assertNotNull(hospitalService.createHospital(dto, Locale.ENGLISH));
        }

        @Test
        @DisplayName("foreign country with blank address and blank poBox throws")
        void foreignBlankBoth() {
            setSuperAdmin();
            HospitalRequestDTO dto = HospitalRequestDTO.builder()
                    .name("Foreign Blank").city("Ouaga").country("BF")
                    .address("  ").poBox("  ").phoneNumber("1234567890").active(true).build();
            stubMessage("hospital.address.required");

            assertThrows(IllegalArgumentException.class,
                    () -> hospitalService.createHospital(dto, Locale.ENGLISH));
        }

        @Test
        @DisplayName("null country treated as foreign")
        void nullCountry() {
            setSuperAdmin();
            HospitalRequestDTO dto = HospitalRequestDTO.builder()
                    .name("Null Country").city("City").address("123 Main")
                    .phoneNumber("1234567890").active(true).build();

            when(hospitalRepository.existsByNameIgnoreCaseAndZipCode(any(), any())).thenReturn(false);
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> {
                Hospital h = inv.getArgument(0);
                h.setId(UUID.randomUUID());
                return h;
            });

            assertNotNull(hospitalService.createHospital(dto, Locale.ENGLISH));
        }
    }

    // ═══════════════ edge cases in private helpers ═══════════════

    @Test
    @DisplayName("hasHospitalAccess returns false for null hospitalId")
    void getHospitalById_nullHospitalIdInScope() {
        // Tests the hasHospitalAccess(null) → false path indirectly
        // by having a hospital with null ID in scope check
        HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(UUID.randomUUID())
                .principalUsername("user")
                .superAdmin(false)
                .permittedHospitalIds(Set.of(UUID.randomUUID()))
                .build());

        UUID id = UUID.randomUUID();
        Hospital hospital = buildHospital(id, "Test", "T-01");
        when(hospitalRepository.findById(id)).thenReturn(Optional.of(hospital));
        stubMessage("access.denied");

        // hospital ID doesn't match scope → AccessDeniedException
        assertThrows(AccessDeniedException.class,
                () -> hospitalService.getHospitalById(id, Locale.ENGLISH));
    }

    @Test
    @DisplayName("applyHospitalScope handles null list")
    void getAllHospitals_nullListFromRepo() {
        when(hospitalRepository.findAllForFilters(any(), any(), any(), any()))
                .thenReturn(null);

        // applyHospitalScope(null) → returns empty list
        List<HospitalResponseDTO> result = hospitalService.getAllHospitals(null, null, null, null, Locale.ENGLISH);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("mapHospitalWithDepartments with null department in set")
    void getHospitalsWithDepartments_nullDeptInSet() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital Null Dept", "HND-01");

        Set<Department> departments = new HashSet<>();
        departments.add(null);
        Department valid = new Department();
        valid.setId(UUID.randomUUID());
        valid.setName("Valid");
        valid.setCode("VAL");
        valid.setHospital(hospital);
        valid.setStaffMembers(new HashSet<>());
        departments.add(valid);
        hospital.setDepartments(departments);

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("toDepartmentSummary with null head of department")
    void getHospitalsWithDepartments_nullHead() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital No Head", "HNH-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("No Head Dept");
        dept.setCode("NHD");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());
        dept.setHeadOfDepartment(null);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
        assertNull(result.get(0).getDepartments().get(0).getHeadOfDepartmentName());
        assertNull(result.get(0).getDepartments().get(0).getHeadOfDepartmentEmail());
    }

    @Test
    @DisplayName("matchesDepartmentQuery with null department")
    void getHospitalsWithDepartments_matchQueryWithNullDept() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital Match", "HM-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName(null); // all null fields
        dept.setCode(null);
        dept.setEmail(null);
        dept.setPhoneNumber(null);
        dept.setHospital(null);
        dept.setHeadOfDepartment(null);
        dept.setStaffMembers(new HashSet<>());

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        // departmentQuery provided but nothing matches → returns null for this hospital
        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "xyz", null, Locale.ENGLISH);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("matchesHospitalForDepartment with hospital code match")
    void getHospitalsWithDepartments_matchByHospitalCode() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital X", "XCODE");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Some Dept");
        dept.setCode("SD");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "xcode", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("matchesDepartmentBasics matches by department code")
    void getHospitalsWithDepartments_matchByDeptCode() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital Z", "HZ-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Something");
        dept.setCode("UNIQUE-CODE-XYZ");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "unique-code", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("matchesDepartmentBasics matches by department email")
    void getHospitalsWithDepartments_matchByDeptEmail() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital W", "HW-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dept");
        dept.setCode("D");
        dept.setEmail("unique-email@dept.com");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "unique-email", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("matchesDepartmentBasics matches by department phone")
    void getHospitalsWithDepartments_matchByDeptPhone() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital V", "HV-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dept");
        dept.setCode("D");
        dept.setPhoneNumber("999-888-7777");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "999-888", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("toDepartmentSummary passes parent hospital's id/name (not dept's hospital)")
    void getHospitalsWithDepartments_nullHospitalInDept() {
        UUID parentId = UUID.randomUUID();
        Hospital hospital = buildHospital(parentId, "Hospital NN", "NN-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dept NN");
        dept.setCode("DNN");
        dept.setHospital(null); // null hospital on department — irrelevant, parent is passed
        dept.setStaffMembers(new HashSet<>());

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
        // The parent hospital is always passed, so hospitalId should be the parent's id
        assertEquals(parentId, result.get(0).getDepartments().get(0).getHospitalId());
    }

    @Test
    @DisplayName("toDepartmentSummary with null staffMembers returns 0 staffCount")
    void getHospitalsWithDepartments_nullStaffMembers() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital SM", "SM-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dept SM");
        dept.setCode("DSM");
        dept.setHospital(hospital);
        dept.setStaffMembers(null); // null staff members

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
        assertEquals(0, result.get(0).getDepartments().get(0).getStaffCount());
    }

    @Test
    @DisplayName("buildFullName with null firstName and non-null lastName")
    void getHospitalsWithDepartments_headUserLastNameOnly() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital FN", "FN-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dept FN");
        dept.setCode("DFN");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());

        Staff head = new Staff();
        User headUser = new User();
        headUser.setFirstName(null);
        headUser.setLastName("OnlyLast");
        headUser.setEmail("fn@hospital.com");
        head.setUser(headUser);
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals("OnlyLast", result.get(0).getDepartments().get(0).getHeadOfDepartmentName());
    }

    @Test
    @DisplayName("applyHospitalScope filters out hospital with null id")
    void getAllHospitals_hospitalWithNullIdFiltered() {
        UUID scopedId = UUID.randomUUID();
        setScopedContext(scopedId);

        Hospital h1 = buildHospital(scopedId, "Scoped", "S-01");
        Hospital h2 = Hospital.builder().name("Null ID").code("N-01").active(true).build();
        // h2 has null id

        when(hospitalRepository.findAllForFilters(any(), any(), any(), any()))
                .thenReturn(List.of(h1, h2));

        List<HospitalResponseDTO> result = hospitalService.getAllHospitals(null, null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
        assertEquals("Scoped", result.get(0).getName());
    }

    @Test
    @DisplayName("applyHospitalScope filters out null hospital in list")
    void getAllHospitals_nullHospitalInList() {
        UUID scopedId = UUID.randomUUID();
        setScopedContext(scopedId);

        Hospital h1 = buildHospital(scopedId, "Scoped", "S-01");
        java.util.ArrayList<Hospital> hospitals = new java.util.ArrayList<>();
        hospitals.add(h1);
        hospitals.add(null);

        when(hospitalRepository.findAllForFilters(any(), any(), any(), any()))
                .thenReturn(hospitals);

        List<HospitalResponseDTO> result = hospitalService.getAllHospitals(null, null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("isAnonymousContext returns true when principalUsername is null but principalUserId is not")
    void getHospitalById_withPrincipalUserIdButNoUsername() {
        // This tests a branch where principalUserId is set but principalUsername is null
        // isAnonymousContext returns false because principalUserId is not null
        UUID hospitalId2 = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(UUID.randomUUID())
                .principalUsername(null)
                .superAdmin(false)
                .permittedHospitalIds(Set.of(hospitalId2))
                .build());

        Hospital hospital = buildHospital(hospitalId2, "Test", "T-01");
        when(hospitalRepository.findById(hospitalId2)).thenReturn(Optional.of(hospital));

        HospitalResponseDTO result = hospitalService.getHospitalById(hospitalId2, Locale.ENGLISH);
        assertEquals("Test", result.getName());
    }

    // ═══════════════ mapHospitalWithDepartments: null departments on hospital ═══════════════

    @Test
    @DisplayName("getHospitalsWithDepartments with null departments set returns all departments empty")
    void getHospitalsWithDepartments_nullDepartmentsOnHospital() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "NullDepts", "ND-01");
        hospital.setDepartments(null); // null departments

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
        assertTrue(result.get(0).getDepartments().isEmpty());
    }

    // ═══════════════ toDepartmentSummary: head with user == null but name set ═══════════════

    @Test
    @DisplayName("toDepartmentSummary: head has no user but has name → uses staff name")
    void getHospitalsWithDepartments_headNoUserButHasName() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital HNU", "HNU-01");

        Staff head = new Staff();
        head.setId(UUID.randomUUID());
        head.setUser(null); // no user
        head.setName("Dr. Smith from Staff");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dept HNU");
        dept.setCode("DHNU");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
        DepartmentSummaryDTO deptResult = result.get(0).getDepartments().get(0);
        assertEquals("Dr. Smith from Staff", deptResult.getHeadOfDepartmentName());
        assertNull(deptResult.getHeadOfDepartmentEmail());
    }

    // ═══════════════ toDepartmentSummary: head user returns blank fullName, staff name null ═══════════════

    @Test
    @DisplayName("toDepartmentSummary: head user blank name and staff name null → headName is blank")
    void getHospitalsWithDepartments_headBlankNameStaffNameNull() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital BN", "BN-01");

        User headUser = new User();
        headUser.setId(UUID.randomUUID());
        headUser.setFirstName("  ");
        headUser.setLastName("  ");

        Staff head = new Staff();
        head.setId(UUID.randomUUID());
        head.setUser(headUser);
        head.setName(null); // null staff name

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dept BN");
        dept.setCode("DBN");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, null, null, Locale.ENGLISH);
        assertEquals(1, result.size());
        // headName will be blank from buildFullName, then isBlank()=true, head.getName()=null → stays blank
    }

    // ═══════════════ matchesDepartmentQuery: all three matchers fail → dept filtered ═══════════════

    @Test
    @DisplayName("matchesDepartmentQuery returns false when no field matches the query")
    void getHospitalsWithDepartments_noFieldMatchesQuery() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Unrelated Hospital", "UH-01");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Cardiology");
        dept.setCode("CARD");
        dept.setEmail("cardio@hospital.com");
        dept.setPhoneNumber("555-1234");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());
        dept.setHeadOfDepartment(null);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        // Query doesn't match any department field, head, or hospital
        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "zzzzNonExistentzzz", null, Locale.ENGLISH);
        // No departments match → hospital filtered out
        assertTrue(result.isEmpty());
    }

    // ═══════════════ matchesHeadOfDepartment: head.getName() matches, user doesn't ═══════════════

    @Test
    @DisplayName("matchesHeadOfDepartment: head staff name matches but user doesn't")
    void getHospitalsWithDepartments_headStaffNameMatchesOnly() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital HSN", "HSN-01");

        User headUser = new User();
        headUser.setId(UUID.randomUUID());
        headUser.setEmail("other@hospital.com");
        headUser.setFirstName("Other");
        headUser.setLastName("Person");

        Staff head = new Staff();
        head.setId(UUID.randomUUID());
        head.setUser(headUser);
        head.setName("Dr. SpecialQuery");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dept X");
        dept.setCode("DX");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "specialquery", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    // ═══════════════ matchesHospitalForDepartment: match by hospital code only ═══════════════

    @Test
    @DisplayName("matchesHospitalForDepartment: match by hospital code, not name")
    void getHospitalsWithDepartments_matchByHospitalCodeOnly() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "General Hospital", "UNIQUECODE99");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Surgery");
        dept.setCode("SURG");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());
        dept.setHeadOfDepartment(null);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        // Query matches hospital code, not department name/code/email/phone, not head, not hospital name
        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "uniquecode99", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    // ═══════════════ matchesHeadOfDepartment: user email matches ═══════════════

    @Test
    @DisplayName("matchesHeadOfDepartment: user email matches query but fullName doesn't")
    void getHospitalsWithDepartments_headUserEmailMatchesOnly() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital EMQ", "EMQ-01");

        User headUser = new User();
        headUser.setId(UUID.randomUUID());
        headUser.setEmail("specialemail@hospital.com");
        headUser.setFirstName("John");
        headUser.setLastName("Doe");

        Staff head = new Staff();
        head.setId(UUID.randomUUID());
        head.setUser(headUser);
        head.setName("Regular Name");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dept EMQ");
        dept.setCode("DEMQ");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "specialemail", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }

    // ═══════════════ matchesHeadOfDepartment: user fullName matches (not email, not staff name) ═══════════════

    @Test
    @DisplayName("matchesHeadOfDepartment: user fullName matches query")
    void getHospitalsWithDepartments_headUserFullNameMatchesOnly() {
        Hospital hospital = buildHospital(UUID.randomUUID(), "Hospital FNM", "FNM-01");

        User headUser = new User();
        headUser.setId(UUID.randomUUID());
        headUser.setEmail("nope@nope.com");
        headUser.setFirstName("Unique");
        headUser.setLastName("FullNameMatch");

        Staff head = new Staff();
        head.setId(UUID.randomUUID());
        head.setUser(headUser);
        head.setName("NoMatch");

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Dept FNM");
        dept.setCode("DFNM");
        dept.setHospital(hospital);
        dept.setStaffMembers(new HashSet<>());
        dept.setHeadOfDepartment(head);

        hospital.setDepartments(new HashSet<>(List.of(dept)));

        when(hospitalRepository.findAllWithDepartments(null, null)).thenReturn(List.of(hospital));

        List<HospitalWithDepartmentsDTO> result = hospitalService.getHospitalsWithDepartments(null, "fullnamematch", null, Locale.ENGLISH);
        assertEquals(1, result.size());
    }
}
