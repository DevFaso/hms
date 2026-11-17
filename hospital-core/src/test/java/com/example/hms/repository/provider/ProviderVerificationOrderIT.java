package com.example.hms.repository.provider;

import com.example.hms.BaseIT;
import com.example.hms.enums.FacilityType;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.provider.ProviderVerification;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.LinkedTestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The current verification and the verification history use ONE order:
 * {@code created_at} newest first, then the id. Two rows written in the same
 * instant must never make the "current" row and the first history row differ.
 */
class ProviderVerificationOrderIT extends BaseIT {

    @Autowired private ProviderVerificationRepository verificationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private LinkedTestAccounts accounts;
    private final List<UUID> verificationIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        verificationRepository.deleteAllByIdInBatch(verificationIds);
        verificationIds.clear();
        if (accounts != null) {
            accounts.cleanUp();
        }
    }

    @Test
    @DisplayName("a created_at tie: the current verification is the first history row, the higher id")
    void tieIsBrokenTheSameWayInBothReads() {
        accounts = new LinkedTestAccounts(hospitalRepository, userRepository, roleRepository,
            assignmentRepository, auditEventLogRepository);
        Hospital facility = accounts.provider("Order Pharmacy", FacilityType.PHARMACY);
        LocalDateTime instant = LocalDateTime.of(2026, 10, 1, 9, 0);
        for (int i = 0; i < 4; i++) {
            UUID id = verificationRepository.save(rejected(facility, "LIC-" + i)).getId();
            verificationIds.add(id);
            jdbcTemplate.update("UPDATE hospital.provider_verifications SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(instant), id);
        }

        // The database's own id order (Java's UUID.compareTo is signed and differs).
        UUID highestId = jdbcTemplate.queryForObject(
            "SELECT id FROM hospital.provider_verifications WHERE hospital_id = ? ORDER BY id DESC LIMIT 1",
            UUID.class, facility.getId());
        ProviderVerification current = verificationRepository
            .findFirstByHospital_IdOrderByCreatedAtDescIdDesc(facility.getId()).orElseThrow();
        List<ProviderVerification> history = verificationRepository
            .findByHospital_IdOrderByCreatedAtDescIdDesc(facility.getId());

        assertThat(current.getId()).isEqualTo(highestId);
        assertThat(history).hasSize(4);
        assertThat(history.get(0).getId()).isEqualTo(current.getId());
    }

    private static ProviderVerification rejected(Hospital facility, String licence) {
        ProviderVerification v = new ProviderVerification();
        v.setHospital(facility);
        v.setStatus(ProviderVerificationStatus.REJECTED);
        v.setLegalName("Pharmacie Ordre SARL");
        v.setLegalStructure("SARL");
        v.setRccmNumber("RCCM-" + licence);
        v.setIfuNumber("IFU-" + licence);
        v.setCnssNumber("CNSS-" + licence);
        v.setAddressCity("Ouagadougou");
        v.setAddressRegion("Centre");
        v.setCompanyPhone("+22625000000");
        v.setManagerName("Manager");
        v.setManagerTitle("Gérant");
        v.setBusinessStartedOn(LocalDate.of(2020, 1, 1));
        v.setLicenceNumber(licence);
        v.setLicenceAuthority("DGPML");
        v.setResponsibleProfessionalName("Responsible");
        v.setResponsibleProfessionalRegistration("ORDRE-1");
        return v;
    }
}
