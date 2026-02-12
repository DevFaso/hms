package com.example.hms.repository;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class MaternalHistoryRepositoryTest {

    @Autowired
    private MaternalHistoryRepository maternalHistoryRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Patient patient;
    private Hospital hospital;
    private Staff staff;
    private MaternalHistory maternalHistory1;
    private MaternalHistory maternalHistory2;

        @BeforeEach
    void setUp() {
        // Create Hospital
        hospital = Hospital.builder()
                .name("Test Hospital")
                .code("TEST001")
                .address("Test Address")
                .phoneNumber("1234567890")
                .build();
        entityManager.persistAndFlush(hospital);

        // Create User for Patient
        User patientUser = User.builder()
                .username("patientuser")
                .email("patient@example.com")
                .passwordHash("encoded")
                .phoneNumber("1234567890")
                .isActive(true)
                .build();
        entityManager.persistAndFlush(patientUser);

        // Create Patient with User
        patient = Patient.builder()
                .firstName("Test")
                .lastName("Patient")
                .dateOfBirth(LocalDate.now().minusYears(25))
                .gender("FEMALE")
                .phoneNumberPrimary("1234567890")
                .email("patient@example.com")
                .hospitalId(hospital.getId())
                .user(patientUser)
                .build();
        entityManager.persistAndFlush(patient);

        // Create User for Staff
        User staffUser = User.builder()
                .username("doctor1")
                .email("doctor@example.com")
                .passwordHash("encoded")
                .phoneNumber("0987654321")
                .isActive(true)
                .build();
        staffUser = entityManager.persistAndFlush(staffUser);

        // Create Role for staff
        com.example.hms.model.Role role = com.example.hms.model.Role.builder()
                .code("ROLE_DOCTOR")
                .name("Doctor")
                .build();
        role = entityManager.persistAndFlush(role);

        // Create UserRole for the staff user
        com.example.hms.model.UserRole userRole = com.example.hms.model.UserRole.builder()
                .id(new com.example.hms.model.UserRoleId(staffUser.getId(), role.getId()))
                .user(staffUser)
                .role(role)
                .build();
        entityManager.persistAndFlush(userRole);

        // Create UserRoleHospitalAssignment (required for Staff)
        com.example.hms.model.UserRoleHospitalAssignment assignment = 
                com.example.hms.model.UserRoleHospitalAssignment.builder()
                .user(staffUser)
                .role(role)
                .hospital(hospital)
                .build();
        assignment = entityManager.persistAndFlush(assignment);

        // Create staff member
        staff = Staff.builder()
                .user(staffUser)
                .hospital(hospital)
                .assignment(assignment)
                .licenseNumber("LIC-TEST-12345")
                .name("Dr. Test Staff")
                .jobTitle(JobTitle.DOCTOR)
                .employmentType(EmploymentType.FULL_TIME)
                .startDate(LocalDate.now())
                .active(true)
                .build();
        staff = entityManager.persistAndFlush(staff);

        // Create first maternal history record
        maternalHistory1 = MaternalHistory.builder()
                .patient(patient)
                .hospital(hospital)
                .recordedBy(staff)
                .recordedDate(LocalDateTime.now().minusDays(10))
                .versionNumber(1)
                .riskCategory("LOW")
                .dataComplete(true)
                .reviewedByProvider(true)
                .build();
        entityManager.persistAndFlush(maternalHistory1);

        // Create second maternal history record (more recent)
        maternalHistory2 = MaternalHistory.builder()
                .patient(patient)
                .hospital(hospital)
                .recordedBy(staff)
                .recordedDate(LocalDateTime.now())
                .versionNumber(2)
                .riskCategory("MODERATE")
                .dataComplete(true)
                .reviewedByProvider(false)
                .build();
        entityManager.persistAndFlush(maternalHistory2);
    }

    @Test
    void findByPatientIdOrderByRecordedDateDesc_shouldReturnAllVersionsInDescendingOrder() {
        List<MaternalHistory> results = maternalHistoryRepository
                .findByPatientIdOrderByRecordedDateDesc(patient.getId());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getVersionNumber()).isEqualTo(2);
        assertThat(results.get(1).getVersionNumber()).isEqualTo(1);
    }

    @Test
    void findCurrentByPatientId_shouldReturnLatestVersion() {
        Optional<MaternalHistory> result = maternalHistoryRepository
                .findCurrentByPatientId(patient.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getVersionNumber()).isEqualTo(2);
        assertThat(result.get().getId()).isEqualTo(maternalHistory2.getId());
    }

    @Test
    void findByPatientIdAndVersion_shouldReturnSpecificVersion() {
        Optional<MaternalHistory> result = maternalHistoryRepository
                .findByPatientIdAndVersion(patient.getId(), 1);

        assertThat(result).isPresent();
        assertThat(result.get().getVersionNumber()).isEqualTo(1);
        assertThat(result.get().getId()).isEqualTo(maternalHistory1.getId());
    }

    @Test
    void findAllVersionsByPatientId_shouldReturnAllVersionsDescending() {
        List<MaternalHistory> results = maternalHistoryRepository
                .findAllVersionsByPatientId(patient.getId());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getVersionNumber()).isEqualTo(2);
        assertThat(results.get(1).getVersionNumber()).isEqualTo(1);
    }

    @Test
    void findByHospitalIdOrderByRecordedDateDesc_shouldReturnPaginatedResults() {
        Page<MaternalHistory> results = maternalHistoryRepository
                .findByHospitalIdOrderByRecordedDateDesc(hospital.getId(), PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(2);
        assertThat(results.getTotalElements()).isEqualTo(2);
    }

    @Test
    void findByPatientIdAndDateRange_shouldFilterByDateRange() {
        LocalDateTime startDate = LocalDateTime.now().minusDays(7);
        LocalDateTime endDate = LocalDateTime.now();

        List<MaternalHistory> results = maternalHistoryRepository
                .findByPatientIdAndDateRange(patient.getId(), startDate, endDate);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getVersionNumber()).isEqualTo(2);
    }

    @Test
    void searchMaternalHistory_shouldFilterByRiskCategory() {
        Page<MaternalHistory> results = maternalHistoryRepository.searchMaternalHistory(
                hospital.getId(),
                null,
                "MODERATE",
                null,
                null,
                null,
                null,
                PageRequest.of(0, 10)
        );

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getRiskCategory()).isEqualTo("MODERATE");
    }

    @Test
    void searchMaternalHistory_shouldFilterByDataComplete() {
        Page<MaternalHistory> results = maternalHistoryRepository.searchMaternalHistory(
                hospital.getId(),
                null,
                null,
                true,
                null,
                null,
                null,
                PageRequest.of(0, 10)
        );

        assertThat(results.getContent()).hasSize(2);
    }

    @Test
    void searchMaternalHistory_shouldFilterByReviewedByProvider() {
        Page<MaternalHistory> results = maternalHistoryRepository.searchMaternalHistory(
                hospital.getId(),
                null,
                null,
                null,
                true,
                null,
                null,
                PageRequest.of(0, 10)
        );

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getReviewedByProvider()).isTrue();
    }

    @Test
    void findHighRiskByHospital_shouldReturnHighRiskCases() {
        // Create high-risk case
        MaternalHistory highRiskCase = MaternalHistory.builder()
                .patient(patient)
                .hospital(hospital)
                .recordedBy(staff)
                .recordedDate(LocalDateTime.now())
                .versionNumber(3)
                .preeclampsiaHistory(true)
                .riskCategory("HIGH")
                .dataComplete(true)
                .build();
        entityManager.persistAndFlush(highRiskCase);

        Page<MaternalHistory> results = maternalHistoryRepository
                .findHighRiskByHospital(hospital.getId(), PageRequest.of(0, 10));

        assertThat(results.getContent()).isNotEmpty();
        assertThat(results.getContent()).anyMatch(mh -> 
                Boolean.TRUE.equals(mh.getPreeclampsiaHistory()) || "HIGH".equals(mh.getRiskCategory()));
    }

    @Test
    void findPendingReviewByHospital_shouldReturnUnreviewedCompleteRecords() {
        Page<MaternalHistory> results = maternalHistoryRepository
                .findPendingReviewByHospital(hospital.getId(), PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getReviewedByProvider()).isFalse();
        assertThat(results.getContent().get(0).getDataComplete()).isTrue();
    }

    @Test
    void findRequiringSpecialistReferral_shouldReturnCasesNeedingReferral() {
        maternalHistory2.setRequiresSpecialistReferral(true);
        entityManager.merge(maternalHistory2);
        entityManager.flush();

        Page<MaternalHistory> results = maternalHistoryRepository
                .findRequiringSpecialistReferral(hospital.getId(), PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getRequiresSpecialistReferral()).isTrue();
    }

    @Test
    void findWithPsychosocialConcerns_shouldReturnCasesWithConcerns() {
        maternalHistory2.setDomesticViolenceConcerns(true);
        maternalHistory2.setAnxietyPresent(true);
        entityManager.merge(maternalHistory2);
        entityManager.flush();

        Page<MaternalHistory> results = maternalHistoryRepository
                .findWithPsychosocialConcerns(hospital.getId(), PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getDomesticViolenceConcerns()).isTrue();
    }

    @Test
    void findMaxVersionByPatientId_shouldReturnHighestVersion() {
        Integer maxVersion = maternalHistoryRepository
                .findMaxVersionByPatientId(patient.getId());

        assertThat(maxVersion).isEqualTo(2);
    }

    @Test
    void countHighRiskByHospital_shouldCountHighRiskCases() {
        maternalHistory1.setRiskCategory("HIGH");
        entityManager.merge(maternalHistory1);
        entityManager.flush();

        long count = maternalHistoryRepository.countHighRiskByHospital(hospital.getId());

        assertThat(count).isGreaterThan(0);
    }

    @Test
    void countPendingReviewByHospital_shouldCountUnreviewedCases() {
        long count = maternalHistoryRepository.countPendingReviewByHospital(hospital.getId());

        assertThat(count).isEqualTo(1);
    }

    @Test
    void existsByPatient_Id_shouldReturnTrueWhenRecordsExist() {
        boolean exists = maternalHistoryRepository.existsByPatient_Id(patient.getId());

        assertThat(exists).isTrue();
    }

    @Test
    void existsByPatient_Id_shouldReturnFalseWhenNoRecordsExist() {
        UUID nonExistentPatientId = UUID.randomUUID();

        boolean exists = maternalHistoryRepository.existsByPatient_Id(nonExistentPatientId);

        assertThat(exists).isFalse();
    }

    @Test
    void findIncompleteByHospital_shouldReturnIncompleteRecords() {
        maternalHistory1.setDataComplete(false);
        entityManager.merge(maternalHistory1);
        entityManager.flush();

        Page<MaternalHistory> results = maternalHistoryRepository
                .findIncompleteByHospital(hospital.getId(), PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getDataComplete()).isFalse();
    }

    @Test
    void findWithChronicConditions_shouldReturnPatientsWithConditions() {
        maternalHistory2.setDiabetes(true);
        maternalHistory2.setHypertension(true);
        entityManager.merge(maternalHistory2);
        entityManager.flush();

        Page<MaternalHistory> results = maternalHistoryRepository
                .findWithChronicConditions(hospital.getId(), PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getDiabetes()).isTrue();
    }
}
