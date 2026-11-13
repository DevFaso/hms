package com.example.hms.security.tenant;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.FacilityType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdmissionMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.repository.AdmissionOrderSetRepository;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.specification.TenantScopeSpecification;
import com.example.hms.service.AdmissionStayEncounterService;
import com.example.hms.service.BedAssignmentService;
import com.example.hms.service.EncounterAutoCompletionService;
import com.example.hms.service.impl.AdmissionServiceImpl;
import com.example.hms.service.recordaccess.BreakGlassGate;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.SensitivityClassifier;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** Actual scope producer, read-predicate builder and admission service guards; all repositories are stubs. */
class OrganisationIsolationCompositionTest {

    private static final UUID USER = UUID.randomUUID();
    private final Map<String, Hospital> hospitals = new HashMap<>();
    private final Map<UUID, Admission> rows = new HashMap<>();
    private TenantRoleAssignmentAccessor assignments;
    private ActingScopeResolver resolver;
    private AdmissionRepository repository;
    private AdmissionMapper mapper;
    private AdmissionStayEncounterService stayEncounters;
    private BedAssignmentService beds;
    private BillingInvoiceRepository invoices;
    private AdmissionServiceImpl service;
    private RecordAccessPolicy recordAccessPolicy;
    private CrossHospitalReachRecorder reachRecorder;
    private SensitivityClassifier sensitivityClassifier;
    private BreakGlassGate breakGlassGate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        assignments = mock(TenantRoleAssignmentAccessor.class);
        var assignmentRepository = mock(UserRoleHospitalAssignmentRepository.class);
        ObjectProvider<CrossTenantReadAudit> audit = mock(ObjectProvider.class);
        resolver = new ActingScopeResolver(assignments, assignmentRepository, audit);
        repository = mock(AdmissionRepository.class);
        mapper = mock(AdmissionMapper.class);
        stayEncounters = mock(AdmissionStayEncounterService.class);
        beds = mock(BedAssignmentService.class);
        invoices = mock(BillingInvoiceRepository.class);
        recordAccessPolicy = mock(RecordAccessPolicy.class);
        reachRecorder = mock(CrossHospitalReachRecorder.class);
        sensitivityClassifier = mock(SensitivityClassifier.class);
        breakGlassGate = mock(BreakGlassGate.class);
        service = new AdmissionServiceImpl(repository, mock(AdmissionOrderSetRepository.class),
            mock(PatientRepository.class), mock(HospitalRepository.class), mock(StaffRepository.class),
            mock(DepartmentRepository.class), mock(PatientHospitalRegistrationRepository.class),
            mock(EncounterAutoCompletionService.class), stayEncounters, beds, mapper,
            new RoleValidator(assignmentRepository, resolver), recordAccessPolicy,
            reachRecorder, sensitivityClassifier, breakGlassGate, invoices);
        for (String name : List.of("A", "B")) {
            Organization organization = new Organization();
            organization.setId(UUID.randomUUID());
            organization.setName("cdx-org-" + name);
            Hospital hospital = new Hospital();
            hospital.setId(UUID.randomUUID());
            hospital.setName("cdx-hospital-" + name);
            hospital.setOrganization(organization);
            hospitals.put(name, hospital);
            Admission admission = new Admission();
            admission.setId(UUID.randomUUID());
            admission.setHospital(hospital);
            admission.setStatus(AdmissionStatus.ACTIVE);
            rows.put(hospital.getId(), admission);
        }
        when(repository.findById(any(UUID.class))).thenAnswer(invocation -> rows.values().stream()
            .filter(row -> row.getId().equals(invocation.getArgument(0))).findFirst());
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private HospitalContext actAt(Hospital hospital, String requestedHospital) {
        when(assignments.findAssignmentsForUser(USER)).thenReturn(List.of(new TenantRoleAssignment(
            hospital.getId(), hospital.getOrganization().getId(), "DOCTOR", "DOCTOR", true, FacilityType.HOSPITAL)));
        var request = new MockHttpServletRequest("GET", "/api/admissions");
        if (requestedHospital != null) request.addHeader("X-Hospital-Id", requestedHospital);
        HospitalContext context = resolver.withHeader(resolver.liveContext(USER, "cdx-doctor"), request);
        HospitalContextHolder.setContext(context);
        return context;
    }

    private void assertForeignReadAndWriteRefused(Admission foreign) {
        assertThatThrownBy(() -> service.getAdmission(foreign.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.cancelAdmission(foreign.getId(), "cdx negative isolation probe"))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(foreign.getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        verify(repository, times(2)).findById(foreign.getId());
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(recordAccessPolicy, reachRecorder, sensitivityClassifier, breakGlassGate);
        verifyNoInteractions(mapper, stayEncounters, beds, invoices);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "B"})
    void anAssignedOrganisationCannotReadOrCancelTheOtherOrganisationsStay(String name) {
        Hospital own = hospitals.get(name);
        Hospital foreign = hospitals.get(name.equals("A") ? "B" : "A");
        assertThat(own.getOrganization().getId()).isNotEqualTo(foreign.getOrganization().getId());
        actAt(own, own.getId().toString());
        assertForeignReadAndWriteRefused(rows.get(foreign.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "B"})
    void aForeignHospitalHeaderCannotCreatePermission(String name) {
        Hospital own = hospitals.get(name);
        Hospital foreign = hospitals.get(name.equals("A") ? "B" : "A");
        HospitalContext context = actAt(own, foreign.getId().toString());
        assertThat(ActingScopeResolver.scopeOf(context))
            .isEqualTo(new ActingScope.Refused(ActingScope.Reason.NOT_PERMITTED));
        Admission row = rows.get(foreign.getId());
        assertThatThrownBy(() -> service.getAdmission(row.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.cancelAdmission(row.getId(), "cdx negative header probe"))
            .isInstanceOf(BusinessException.class);
        assertThat(row.getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        verify(repository, never()).save(any(Admission.class));
        verifyNoInteractions(mapper, stayEncounters, beds, invoices);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "B"})
    @SuppressWarnings("unchecked")
    void ownScopeBuildsHospitalOnlyPredicateAndCanReadItsOwnStay(String name) {
        Hospital own = hospitals.get(name);
        HospitalContext context = actAt(own, null);
        assertThat(context.getPermittedOrganizationIds()).containsExactly(own.getOrganization().getId());
        Root<ImagingOrder> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        doThrow(new IllegalArgumentException("no direct hospitalId field")).when(root).get("hospitalId");
        Path<Object> hospitalPath = mock(Path.class);
        when(root.get("hospital").get("id")).thenReturn(hospitalPath);
        Predicate ownOnly = mock(Predicate.class);
        when(hospitalPath.in(Set.of(own.getId()))).thenReturn(ownOnly);
        assertThat(new TenantScopeSpecification<>(ImagingOrder.class).toPredicate(root, query, builder)).isSameAs(ownOnly);
        verify(root, never()).get("organization");
        verify(root, never()).get("organizationId");
        verify(builder, never()).or(any(Predicate[].class));
        Admission admission = rows.get(own.getId());
        AdmissionResponseDTO response = new AdmissionResponseDTO();
        response.setId(admission.getId());
        response.setHospitalId(own.getId());
        when(mapper.toResponseDTO(admission)).thenReturn(response);
        assertThat(service.getAdmission(admission.getId())).isSameAs(response);
    }

    @Test
    void anUnassignedSiblingHospitalIsNotGrantedBySharingTheOrganisation() {
        Hospital own = hospitals.get("A");
        Hospital sibling = hospitals.get("B");
        sibling.setOrganization(own.getOrganization());
        actAt(own, null);
        assertForeignReadAndWriteRefused(rows.get(sibling.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "B"})
    void anOrganisationWithoutAHospitalAssignmentGrantsNoHospitalScope(String name) {
        Hospital own = hospitals.get(name);
        when(assignments.findAssignmentsForUser(USER)).thenReturn(List.of(new TenantRoleAssignment(
            null, own.getOrganization().getId(), "HOSPITAL_ADMIN", "HOSPITAL_ADMIN", true, null)));
        HospitalContext context = resolver.liveContext(USER, "cdx-org-only");
        HospitalContextHolder.setContext(context);
        assertThat(ActingScopeResolver.readableHospitalIds(context)).isEmpty();
        assertThat(ActingScopeResolver.scopeOf(context)).isInstanceOf(ActingScope.Refused.class);
        assertThatThrownBy(() -> service.getAdmission(rows.get(own.getId()).getId())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper, stayEncounters, beds, invoices);
    }
}
