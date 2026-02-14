package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.ProblemChangeType;
import com.example.hms.enums.ProblemSeverity;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientProblemHistory;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientDiagnosisDeleteRequestDTO;
import com.example.hms.payload.dto.PatientDiagnosisRequestDTO;
import com.example.hms.payload.dto.PatientDiagnosisUpdateRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemHistoryRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
class PatientControllerDiagnosisIT extends BaseIT {

	private static final String API_CONTEXT = "/api";
	@SuppressWarnings("java:S1075")
	private static final String PATIENT_DIAGNOSES_PATH = "/patients/{id}/diagnoses";
	@SuppressWarnings("java:S1075")
	private static final String PATIENT_DIAGNOSIS_ITEM_PATH = "/patients/{id}/diagnoses/{diagnosisId}";
	private static final String HOSPITAL_ID_PARAM = "hospitalId";
	private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
	private static final String ICD_ACUTE_BRONCHITIS = "J20.9";
	private static final String STATUS_CHANGE_REASON = "Symptoms resolved";
	private static final String DELETE_REASON = "Entered in error";

	private final AtomicInteger sequence = new AtomicInteger();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private HospitalRepository hospitalRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private UserRoleHospitalAssignmentRepository assignmentRepository;

	@Autowired
	private StaffRepository staffRepository;

	@Autowired
	private PatientRepository patientRepository;

	@Autowired
	private PatientHospitalRegistrationRepository registrationRepository;

	@Autowired
	private PatientProblemRepository patientProblemRepository;

	@Autowired
	private PatientProblemHistoryRepository patientProblemHistoryRepository;

	private Organization organization;
	private Hospital hospital;
	private Patient patient;
	private User doctorUser;
	private Staff doctorStaff;

	@BeforeEach
	void setUp() {
		patientProblemHistoryRepository.deleteAll();
		patientProblemRepository.deleteAll();
		registrationRepository.deleteAll();
		patientRepository.deleteAll();
		staffRepository.deleteAll();
		assignmentRepository.deleteAll();
		userRepository.deleteAll();
		roleRepository.deleteAll();
		hospitalRepository.deleteAll();
		organizationRepository.deleteAll();

		organization = organizationRepository.save(Organization.builder()
			.name("Test Health Network")
			.code("ORG-" + nextId())
			.type(OrganizationType.HOSPITAL_CHAIN)
			.active(true)
			.build());

		hospital = hospitalRepository.save(Hospital.builder()
			.name("Test Teaching Hospital")
			.code("HTH" + nextId())
			.city("Ouagadougou")
			.country("Burkina Faso")
			.address("1 Main St")
			.phoneNumber("+226555" + nextId())
			.email("contact" + nextId() + "@hospital.test")
			.organization(organization)
			.build());

		Role doctorRole = ensureDoctorRole();
		doctorUser = userRepository.save(buildUser("doctor"));
		UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
			.assignmentCode("ASSIGN-" + nextId())
			.description("Primary doctor assignment")
			.user(doctorUser)
			.hospital(hospital)
			.role(doctorRole)
			.startDate(LocalDate.now())
			.assignedAt(LocalDateTime.now())
			.active(true)
			.build());
		doctorStaff = staffRepository.save(Staff.builder()
			.user(doctorUser)
			.hospital(hospital)
			.assignment(assignment)
			.jobTitle(JobTitle.SURGEON)
			.employmentType(EmploymentType.FULL_TIME)
			.licenseNumber("LIC-" + nextId())
			.name("Dr. " + doctorUser.getFirstName())
			.active(true)
			.build());

		patient = createPatient(organization, hospital);
	}

	@Test
	@DisplayName("GET /patients/{id}/diagnoses only returns active entries by default")
	void listDiagnosesActiveOnlyByDefault() throws Exception {
		PatientProblem active = createProblem("Acute malaria", ProblemStatus.ACTIVE, 1);
		createProblem("Resolved pneumonia", ProblemStatus.RESOLVED, 20);

		mockMvc.perform(get(API_CONTEXT + PATIENT_DIAGNOSES_PATH, patient.getId())
			.contextPath(API_CONTEXT)
			.param(HOSPITAL_ID_PARAM, hospital.getId().toString())
			.accept(MediaType.APPLICATION_JSON)
			.with(doctorAuthentication()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(active.getId().toString()))
			.andExpect(jsonPath("$[0].problemDisplay").value("Acute malaria"));
	}

	@Test
	@DisplayName("GET /patients/{id}/diagnoses can include historical entries")
	void listDiagnosesIncludesHistoricalWhenRequested() throws Exception {
		createProblem("Chronic kidney disease", ProblemStatus.ACTIVE, 2);
		PatientProblem resolved = createProblem("Old fracture", ProblemStatus.RESOLVED, 30);

		mockMvc.perform(get(API_CONTEXT + PATIENT_DIAGNOSES_PATH, patient.getId())
			.contextPath(API_CONTEXT)
			.param(HOSPITAL_ID_PARAM, hospital.getId().toString())
			.param("includeHistorical", "true")
			.accept(MediaType.APPLICATION_JSON)
			.with(doctorAuthentication()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[1].id").value(resolved.getId().toString()))
			.andExpect(jsonPath("$[1].status").value("RESOLVED"));
	}

	@Test
	@DisplayName("POST /patients/{id}/diagnoses creates a diagnosis and history entry")
	void createPatientDiagnosis() throws Exception {
		PatientDiagnosisRequestDTO request = PatientDiagnosisRequestDTO.builder()
			.hospitalId(hospital.getId())
			.problemDisplay("Acute bronchitis")
			.problemCode(ICD_ACUTE_BRONCHITIS)
			.icdVersion("ICD-10")
			.severity(ProblemSeverity.MODERATE)
			.onsetDate(LocalDate.now())
			.supportingEvidence("Lab confirmed")
			.diagnosisCodes(List.of(ICD_ACUTE_BRONCHITIS))
			.notes("Patient presented with cough")
			.chronic(false)
			.build();

		mockMvc.perform(post(API_CONTEXT + PATIENT_DIAGNOSES_PATH, patient.getId())
			.contextPath(API_CONTEXT)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsBytes(request))
			.accept(MediaType.APPLICATION_JSON)
			.with(doctorAuthentication()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.problemDisplay").value("Acute bronchitis"))
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		List<PatientProblem> problems = patientProblemRepository.findAll();
		assertThat(problems).hasSize(1);
		PatientProblem stored = problems.get(0);
		assertThat(stored.getProblemCode()).isEqualTo(ICD_ACUTE_BRONCHITIS);
		assertThat(stored.getRecordedBy().getId()).isEqualTo(doctorStaff.getId());

		List<PatientProblemHistory> historyEntries = patientProblemHistoryRepository.findAll();
		assertThat(historyEntries).hasSize(1);
		assertThat(historyEntries.get(0).getChangeType()).isEqualTo(ProblemChangeType.CREATED);
	}

	@Test
	@DisplayName("PUT /patients/{id}/diagnoses/{diagnosisId} transitions status when reason provided")
	void updatePatientDiagnosisStatus() throws Exception {
		PatientProblem problem = createProblem("Gestational diabetes", ProblemStatus.ACTIVE, 3);

		PatientDiagnosisUpdateRequestDTO request = PatientDiagnosisUpdateRequestDTO.builder()
			.hospitalId(hospital.getId())
			.status(ProblemStatus.RESOLVED)
			.changeReason(STATUS_CHANGE_REASON)
			.resolvedDate(LocalDate.now())
			.diagnosisCodes(new ArrayList<>(List.of("O24.4")))
			.notes("Resolved after delivery")
			.build();

		mockMvc.perform(put(API_CONTEXT + PATIENT_DIAGNOSIS_ITEM_PATH, patient.getId(), problem.getId())
			.contextPath(API_CONTEXT)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsBytes(request))
			.accept(MediaType.APPLICATION_JSON)
			.with(doctorAuthentication()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RESOLVED"))
			.andExpect(jsonPath("$.statusChangeReason").value(STATUS_CHANGE_REASON));

		PatientProblem updated = patientProblemRepository.findById(problem.getId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(ProblemStatus.RESOLVED);
		assertThat(updated.getStatusChangeReason()).isEqualTo(STATUS_CHANGE_REASON);
		assertThat(updated.getDiagnosisCodes()).containsExactly("O24.4");

		List<PatientProblemHistory> historyEntries = patientProblemHistoryRepository.findAll();
		assertThat(historyEntries).hasSize(1);
		assertThat(historyEntries.get(0).getChangeType()).isEqualTo(ProblemChangeType.STATUS_CHANGED);
	}

	@Test
	@DisplayName("DELETE /patients/{id}/diagnoses/{diagnosisId} marks diagnosis inactive with justification")
	void deletePatientDiagnosis() throws Exception {
		PatientProblem problem = createProblem("Old injury", ProblemStatus.ACTIVE, 4);
		PatientDiagnosisDeleteRequestDTO request = PatientDiagnosisDeleteRequestDTO.builder()
			.reason(DELETE_REASON)
			.build();

		mockMvc.perform(delete(API_CONTEXT + PATIENT_DIAGNOSIS_ITEM_PATH, patient.getId(), problem.getId())
			.contextPath(API_CONTEXT)
			.param(HOSPITAL_ID_PARAM, hospital.getId().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsBytes(request))
			.accept(MediaType.APPLICATION_JSON)
			.with(doctorAuthentication()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.message").value("Diagnosis removed successfully."));

		PatientProblem deleted = patientProblemRepository.findById(problem.getId()).orElseThrow();
		assertThat(deleted.getStatus()).isEqualTo(ProblemStatus.INACTIVE);
		assertThat(deleted.getStatusChangeReason()).isEqualTo(DELETE_REASON);

		List<PatientProblemHistory> historyEntries = patientProblemHistoryRepository.findAll();
		assertThat(historyEntries).hasSize(1);
		assertThat(historyEntries.get(0).getChangeType()).isEqualTo(ProblemChangeType.DELETED);
		assertThat(historyEntries.get(0).getReason()).isEqualTo(DELETE_REASON);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		HospitalContextHolder.clear();
	}

	private Role ensureDoctorRole() {
		return roleRepository.findByCode(ROLE_DOCTOR)
			.orElseGet(() -> roleRepository.save(Role.builder()
				.name("Doctor")
				.code(ROLE_DOCTOR)
				.description("Physician role")
				.build()));
	}

	private User buildUser(String prefix) {
		String suffix = nextId();
		return User.builder()
			.username(prefix + suffix)
			.passwordHash("hashed-password")
			.email(prefix + suffix + "@example.test")
			.firstName(prefix + "FN")
			.lastName("User" + suffix)
			.phoneNumber("+22677" + suffix)
			.isActive(true)
			.build();
	}

	private Patient createPatient(Organization organization, Hospital hospital) {
		User patientUser = userRepository.save(buildUser("patient"));
		String suffix = nextId();
		Patient newPatient = Patient.builder()
			.firstName("Aminata")
			.lastName("Diallo")
			.dateOfBirth(LocalDate.of(1992, 3, 10))
			.gender("F")
			.address("Patient address")
			.city("Bobo-Dioulasso")
			.country("Burkina Faso")
			.phoneNumberPrimary("+22678" + suffix)
			.email("aminata" + suffix + "@patient.test")
			.emergencyContactName("Issa Diallo")
			.emergencyContactPhone("+22679" + suffix)
			.organizationId(organization.getId())
			.hospitalId(hospital.getId())
			.user(patientUser)
			.build();
		Patient savedPatient = patientRepository.save(newPatient);

		registrationRepository.save(PatientHospitalRegistration.builder()
			.patient(savedPatient)
			.hospital(hospital)
			.mrn("MRN-" + suffix)
			.registrationDate(LocalDate.now())
			.active(true)
			.build());
		return savedPatient;
	}

        private PatientProblem createProblem(String display, ProblemStatus status, int chronologyOffsetDays) {
                LocalDate onsetDate = LocalDate.now().minusDays(chronologyOffsetDays);
                LocalDateTime reviewedAt = LocalDateTime.now().minusDays(chronologyOffsetDays);
                PatientProblem problem = PatientProblem.builder()
                        .patient(patient)
                        .hospital(hospital)
                        .recordedBy(doctorStaff)
                        .problemDisplay(display)
                        .problemCode("A" + nextId())
                        .status(status)
                        .severity(ProblemSeverity.MODERATE)
                        .onsetDate(onsetDate)
                        .resolvedDate(status == ProblemStatus.RESOLVED ? onsetDate.plusDays(1) : null)
                        .lastReviewedAt(reviewedAt)
                        .notes("Test note")
                        .diagnosisCodes(new ArrayList<>(List.of("A" + nextId())))
                        .build();
                return patientProblemRepository.save(problem);
        }	private RequestPostProcessor doctorAuthentication() {
		Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_DOCTOR));
		CustomUserDetails principal = new CustomUserDetails(
			doctorUser.getId(),
			doctorUser.getUsername(),
			doctorUser.getPasswordHash(),
			true,
			authorities);
		Authentication auth = new UsernamePasswordAuthenticationToken(principal, doctorUser.getPasswordHash(), authorities);
		return request -> {
			SecurityContextHolder.getContext().setAuthentication(auth);
			applyDoctorTenantContext();
			request.setUserPrincipal(auth);
			return authentication(auth).postProcessRequest(request);
		};
	}

	private void applyDoctorTenantContext() {
		HospitalContextHolder.setContext(HospitalContext.builder()
			.principalUserId(doctorUser.getId())
			.principalUsername(doctorUser.getUsername())
			.activeOrganizationId(organization.getId())
			.activeHospitalId(hospital.getId())
			.permittedOrganizationIds(Set.of(organization.getId()))
			.permittedHospitalIds(Set.of(hospital.getId()))
			.build());
	}

	private String nextId() {
		return String.format("%05d", sequence.incrementAndGet());
	}
}