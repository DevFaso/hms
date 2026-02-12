package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(authorities = "ROLE_SUPER_ADMIN")
class DepartmentControllerIT extends BaseIT {

	private static final String API_CONTEXT = "/api";

	private final AtomicInteger sequence = new AtomicInteger();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private DepartmentRepository departmentRepository;

	@Autowired
	private HospitalRepository hospitalRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private UserRoleHospitalAssignmentRepository assignmentRepository;

	@BeforeEach
	void cleanDatabase() {
		departmentRepository.deleteAll();
		assignmentRepository.deleteAll();
		hospitalRepository.deleteAll();
		organizationRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	@DisplayName("GET /departments filters by organization id")
	void getAllDepartmentsFiltersByOrganization() throws Exception {
		Organization orgA = createOrganization("ORG-A");
		Organization orgB = createOrganization("ORG-B");

		Hospital hospitalA = createHospital("Org A Hospital", "ORGAH", "Ouagadougou", "Centre", orgA);
		Hospital hospitalB = createHospital("Org B Hospital", "ORGBH", "Bobo-Dioulasso", "Hauts-Bassins", orgB);
		Hospital independent = createHospital("Independent Clinic", "INDEP", "Kaya", "Centre-Nord", null);

		Department deptA = createDepartment("Cardiology", "CARD", hospitalA);
		createDepartment("Neurology", "NEUR", hospitalB);
		createDepartment("Emergency", "EMER", independent);

		mockMvc.perform(get(API_CONTEXT + "/departments").contextPath(API_CONTEXT)
				.param("organizationId", hospitalA.getOrganization().getId().toString())
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(deptA.getId().toString()))
			.andExpect(jsonPath("$[0].hospitalName").value("Org A Hospital"));
	}

	@Test
	@DisplayName("GET /departments returns only hospitals without organization when requested")
	void getAllDepartmentsUnassignedOnly() throws Exception {
		Organization org = createOrganization("ORG-C");
		createDepartment("Surgery", "SURG", createHospital("Chain Hospital", "CHAIN", "Ouagadougou", "Centre", org));
		Department unassigned = createDepartment("Radiology", "RAD", createHospital("Free Hospital", "FREE1", "Fada", "Est", null));

		mockMvc.perform(get(API_CONTEXT + "/departments").contextPath(API_CONTEXT)
				.param("unassignedOnly", "true")
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(unassigned.getId().toString()))
			.andExpect(jsonPath("$[0].hospitalName").value("Free Hospital"));
	}

	@Test
	@DisplayName("GET /departments supports case-insensitive hospital city filtering")
	void getAllDepartmentsCityFilter() throws Exception {
		createDepartment("General", "GEN", createHospital("Capital Clinic", "CAP01", "Ouagadougou", "Centre", null));
		Department western = createDepartment("Intensive Care", "ICU", createHospital("Western Clinic", "WEST1", "Bobo-Dioulasso", "Hauts-Bassins", null));

		mockMvc.perform(get(API_CONTEXT + "/departments").contextPath(API_CONTEXT)
				.param("city", "bobo")
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(western.getId().toString()))
			.andExpect(jsonPath("$[0].hospitalName").value("Western Clinic"));
	}

	private Organization createOrganization(String code) {
		Organization organization = Organization.builder()
			.name("Org " + code)
			.code(code)
			.type(OrganizationType.HOSPITAL_CHAIN)
			.active(true)
			.build();
		return organizationRepository.saveAndFlush(organization);
	}

	private Hospital createHospital(String name, String code, String city, String state, Organization organization) {
		Hospital hospital = Hospital.builder()
			.name(name)
			.code(code)
			.city(city)
			.state(state)
			.country("Burkina Faso")
			.phoneNumber("+226-555-0000")
			.email(code.toLowerCase() + "@hospital.test")
			.active(true)
			.build();
		hospital.setOrganization(organization);
		return hospitalRepository.saveAndFlush(hospital);
	}

	private Department createDepartment(String name, String code, Hospital hospital) {
		UserRoleHospitalAssignment assignment = createAssignment(hospital);
		Department department = Department.builder()
			.name(name)
			.code(code)
			.email(name.toLowerCase().replace(" ", "") + "@dept.test")
			.phoneNumber("+226-111-0000")
			.active(true)
			.hospital(hospital)
			.assignment(assignment)
			.build();
		return departmentRepository.saveAndFlush(department);
	}

	private UserRoleHospitalAssignment createAssignment(Hospital hospital) {
		Role role = ensureHospitalAdminRole();
		User user = createUser();

		UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
			.assignmentCode("ASSIGN-" + nextIdentifier())
			.description("Test assignment")
			.startDate(LocalDate.now())
			.registeredBy(user)
			.user(user)
			.hospital(hospital)
			.role(role)
			.assignedAt(LocalDateTime.now())
			.active(true)
			.build();
		return assignmentRepository.saveAndFlush(assignment);
	}

	private User createUser() {
		String identifier = nextIdentifier();
		User user = User.builder()
			.username("user-" + identifier)
			.passwordHash("password")
			.email(identifier + "@example.com")
			.firstName("User")
			.lastName(identifier)
			.phoneNumber("+226555" + String.format("%04d", sequence.incrementAndGet()))
			.build();
		return userRepository.saveAndFlush(user);
	}

	private Role ensureHospitalAdminRole() {
		return roleRepository.findByCode("ROLE_HOSPITAL_ADMIN")
			.orElseGet(() -> roleRepository.save(Role.builder()
				.name("HOSPITAL_ADMIN")
				.code("ROLE_HOSPITAL_ADMIN")
				.build()));
	}

	private String nextIdentifier() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
	}
}

