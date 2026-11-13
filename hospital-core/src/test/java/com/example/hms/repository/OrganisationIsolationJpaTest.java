package com.example.hms.repository;

import com.example.hms.config.TenantRepositoryConfig;
import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdmissionMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.auth.JpaTenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AdmissionStayEncounterService;
import com.example.hms.service.BedAssignmentService;
import com.example.hms.service.EncounterAutoCompletionService;
import com.example.hms.service.impl.AdmissionServiceImpl;
import com.example.hms.service.recordaccess.BreakGlassGate;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.SensitivityClassifier;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Actual H2 rows, SQL repository queries, assignment resolver and admission service guard. */
@TenantScopedDataJpaTest
@Import(TenantRepositoryConfig.class)
class OrganisationIsolationJpaTest {

    @Autowired private TestEntityManager entityManager;
    @Autowired private DataSource dataSource;
    @Autowired private PatientRepository patients;
    @Autowired private AdmissionRepository admissions;
    @Autowired private UserRoleHospitalAssignmentRepository assignments;
    private final List<Fixture> fixtures = new ArrayList<>();
    private ActingScopeResolver resolver;
    private AdmissionServiceImpl service;
    private AdmissionMapper mapper;
    private AdmissionStayEncounterService stays;
    private BedAssignmentService beds;
    private BillingInvoiceRepository invoices;

    record Fixture(UUID user, UUID hospital, UUID organization, UUID patient, UUID admission, UUID assignment) { }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        HospitalContextHolder.clear();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
        Organization orgA = entityManager.persist(HospitalFixtures.organization("cdx-org-a", "CDX-A"));
        Organization orgB = entityManager.persist(HospitalFixtures.organization("cdx-org-b", "CDX-B"));
        Role doctor = entityManager.persist(Role.builder().name("cdx doctor").code("ROLE_DOCTOR").build());
        fixtures.add(seed(orgA, doctor, "a"));
        fixtures.add(seed(orgB, doctor, "b"));
        fixtures.add(seed(orgA, doctor, "a-sibling"));
        entityManager.flush();
        entityManager.clear();
        ObjectProvider<CrossTenantReadAudit> audit = mock(ObjectProvider.class);
        resolver = new ActingScopeResolver(new JpaTenantRoleAssignmentAccessor(assignments), assignments, audit);
        mapper = mock(AdmissionMapper.class);
        stays = mock(AdmissionStayEncounterService.class);
        beds = mock(BedAssignmentService.class);
        invoices = mock(BillingInvoiceRepository.class);
        service = new AdmissionServiceImpl(admissions, mock(AdmissionOrderSetRepository.class), patients,
            mock(HospitalRepository.class), mock(StaffRepository.class), mock(DepartmentRepository.class),
            mock(PatientHospitalRegistrationRepository.class), mock(EncounterAutoCompletionService.class), stays,
            beds, mapper, new RoleValidator(assignments, resolver), mock(RecordAccessPolicy.class),
            mock(CrossHospitalReachRecorder.class), mock(SensitivityClassifier.class), mock(BreakGlassGate.class), invoices);
    }

    private Fixture seed(Organization organization, Role role, String suffix) {
        Hospital hospital = entityManager.persist(HospitalFixtures.hospital(organization, "cdx-" + suffix, "CDX-" + suffix));
        User user = entityManager.persist(User.builder().username("cdx-doctor-" + suffix)
            .passwordHash("cdx-not-a-credential").email("cdx-doctor-" + suffix + "@example.invalid")
            .firstName("cdx").lastName("Doctor " + suffix).build());
        UserRoleHospitalAssignment assignment = entityManager.persist(UserRoleHospitalAssignment.builder()
            .user(user).role(role).hospital(hospital).active(true).build());
        Staff staff = entityManager.persist(Staff.builder().user(user).hospital(hospital).assignment(assignment)
            .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME).active(true).build());
        User patientUser = entityManager.persist(User.builder().username("cdx-patient-" + suffix)
            .passwordHash("cdx-not-a-credential").email("cdx-patient-user-" + suffix + "@example.invalid")
            .firstName("cdx").lastName("Patient " + suffix).build());
        Patient patient = entityManager.persist(Patient.builder().firstName("cdx").lastName("Patient " + suffix)
            .dateOfBirth(LocalDate.of(1990, 1, 1)).gender("M").address("cdx fictitious address")
            .phoneNumberPrimary("cdx-" + suffix).email("cdx-patient-" + suffix + "@example.invalid")
            .user(patientUser).hospitalId(hospital.getId()).organizationId(organization.getId()).active(true).build());
        entityManager.persist(PatientHospitalRegistration.builder().patient(patient).hospital(hospital)
            .mrn("CDX-MRN-" + suffix).registrationDate(LocalDate.of(2026, 1, 1)).active(true).build());
        Admission admission = new Admission();
        admission.setPatient(patient);
        admission.setHospital(hospital);
        admission.setAdmittingProvider(staff);
        admission.setAdmissionType(AdmissionType.ELECTIVE);
        admission.setAcuityLevel(AcuityLevel.LEVEL_1_MINIMAL);
        admission.setAdmissionDateTime(LocalDateTime.of(2026, 1, 1, 12, 0));
        admission.setChiefComplaint("cdx isolation fixture");
        admission.setStatus(AdmissionStatus.ACTIVE);
        // No JSON payload is needed for scope tests. H2's JSONB domain
        // double-encodes the Hypersistence type's empty-array defaults.
        admission.setSecondaryDiagnoses(null);
        admission.setCustomOrders(null);
        admission.setConsultingPhysicians(null);
        admission.setFollowUpAppointments(null);
        entityManager.persist(admission);
        return new Fixture(user.getId(), hospital.getId(), organization.getId(), patient.getId(), admission.getId(), assignment.getId());
    }

    private void actAt(Fixture own) {
        HospitalContextHolder.clear();
        var context = resolver.liveContext(own.user(), "cdx-doctor");
        HospitalContextHolder.setContext(context);
        assertThat(ActingScopeResolver.readableHospitalIds(context)).containsExactly(own.hospital());
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void ownCancellationPersistsWithoutChangingEitherForeignStay(int index) {
        Fixture own = fixtures.get(index);
        actAt(own);
        service.cancelAdmission(own.admission(), "cdx own-hospital cancellation");
        entityManager.flush();
        entityManager.clear();
        for (Fixture fixture : fixtures) {
            Admission row = entityManager.find(Admission.class, fixture.admission());
            assertThat(row.getStatus()).isEqualTo(fixture == own ? AdmissionStatus.CANCELLED : AdmissionStatus.ACTIVE);
            if (fixture == own) {
                assertThat(row.getCancellationReason()).isEqualTo("cdx own-hospital cancellation");
                assertThat(row.getCancelledAt()).isNotNull();
            } else {
                assertThat(row.getCancellationReason()).isNull();
                assertThat(row.getCancelledAt()).isNull();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void sqlPatientReadsAndSearchesStayWithinTheAssignedHospital(int index) {
        Fixture own = fixtures.get(index);
        actAt(own);
        assertThat(patients.findById(own.patient())).isPresent();
        assertThat(patients.existsById(own.patient())).isTrue();
        assertThat(patients.findAll()).extracting(Patient::getId).containsExactly(own.patient());
        assertThat(patients.findAll(PageRequest.of(0, 1)).getTotalElements()).isEqualTo(1);
        assertThat(patients.searchPatientsExtended(null, "%cdx%", null, null, null, own.hospital(), true,
            PageRequest.of(0, 10)).getContent()).extracting(Patient::getId).containsExactly(own.patient());
        for (Fixture foreign : fixtures) {
            if (foreign == own) continue;
            assertThat(patients.findById(foreign.patient())).isEmpty();
            assertThat(patients.existsById(foreign.patient())).isFalse();
            assertThat(patients.searchPatientsExtended(null, "%cdx%", null, null, null, foreign.hospital(), true,
                PageRequest.of(0, 10)).getContent()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void realAdmissionServiceCannotReadOrCancelForeignPersistedStays(int index) {
        Fixture own = fixtures.get(index);
        actAt(own);
        for (Fixture foreign : fixtures) {
            if (foreign == own) continue;
            assertThatThrownBy(() -> service.getAdmission(foreign.admission())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.cancelAdmission(foreign.admission(), "cdx isolation probe"))
                .isInstanceOf(ResourceNotFoundException.class);
        }
        verifyNoInteractions(mapper, stays, beds, invoices);
        Admission ownRow = admissions.findById(own.admission()).orElseThrow();
        AdmissionResponseDTO dto = new AdmissionResponseDTO();
        dto.setId(own.admission());
        when(mapper.toResponseDTO(ownRow)).thenReturn(dto);
        assertThat(service.getAdmission(own.admission()).getId()).isEqualTo(own.admission());
        entityManager.flush();
        entityManager.clear();
        for (Fixture fixture : fixtures) {
            assertThat(entityManager.find(Admission.class, fixture.admission()).getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void revokingThePersistedAssignmentRefusesTheNextScopeAndWrite(int index) {
        Fixture own = fixtures.get(index);
        actAt(own);
        HospitalContextHolder.clear();
        UserRoleHospitalAssignment assignment = assignments.findById(own.assignment()).orElseThrow();
        assignment.setActive(false);
        assignments.saveAndFlush(assignment);
        entityManager.clear();
        var context = resolver.liveContext(own.user(), "cdx-revoked");
        HospitalContextHolder.setContext(context);
        assertThat(ActingScopeResolver.scopeOf(context)).isInstanceOf(ActingScope.Refused.class);
        assertThat(patients.findById(own.patient())).isEmpty();
        assertThatThrownBy(() -> service.cancelAdmission(own.admission(), "cdx revoked probe"))
            .isInstanceOf(BusinessException.class);
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(Admission.class, own.admission()).getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        verifyNoInteractions(mapper, stays, beds, invoices);
    }
}
