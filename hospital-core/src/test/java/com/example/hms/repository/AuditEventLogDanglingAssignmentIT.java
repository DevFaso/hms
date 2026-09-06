package com.example.hms.repository;

import com.example.hms.enums.ActorType;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Write-audit rows are anchored to the actor's assignment, and assignments are
 * hard-deleted by user removal and {@code DELETE /assignments/..}. On Postgres
 * {@code audit_event_logs.assignment_id} carries no foreign key (V33 only
 * indexes it), so the row outlives its assignment; loading it must then yield a
 * null assignment, not a {@code FetchNotFoundException} that takes the whole
 * audit view down.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TenantContextAccessor.class, EncryptionKeyHolder.class})
class AuditEventLogDanglingAssignmentIT {

    @Autowired
    private AuditEventLogRepository auditEventLogRepository;
    @Autowired
    private TestEntityManager em;

    @Test
    void aRowWhoseAssignmentWasHardDeletedStillLoadsWithoutIt() {
        Organization organization = em.persist(Organization.builder()
            .name("Dangling Org").code("ORG-DA1").type(OrganizationType.HOSPITAL_CHAIN).build());
        Hospital hospital = em.persist(Hospital.builder()
            .name("Dangling Hospital").code("HOSP-DA1").address("1 Rue").city("Ouagadougou").country("BF")
            .organization(organization).build());
        User user = em.persist(User.builder()
            .username("da_nurse_1").passwordHash("h").email("da_nurse_1@example.com")
            .phoneNumber("+22670000911").firstName("Awa").lastName("Nurse").build());
        Role role = em.persist(Role.builder().name("ROLE_DA_NURSE").code("ROLE_DA_NURSE").build());
        UserRoleHospitalAssignment assignment = em.persist(UserRoleHospitalAssignment.builder()
            .user(user).role(role).hospital(hospital).assignedAt(LocalDateTime.now()).active(true).build());
        AuditEventLog row = em.persist(AuditEventLog.builder()
            .eventType(AuditEventType.DATA_UPDATE)
            .eventDescription("PUT /patients/{patientId}/diagnoses/{diagnosisId}")
            .status(AuditStatus.SUCCESS)
            .actorType(ActorType.USER)
            .user(user)
            .assignment(assignment)
            .build());
        em.flush();
        em.clear();

        em.getEntityManager()
            .createNativeQuery("delete from \"security\".user_role_hospital_assignment where id = :id")
            .setParameter("id", assignment.getId())
            .executeUpdate();
        em.clear();

        AuditEventLog reloaded = auditEventLogRepository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getAssignment()).isNull();
        assertThat(reloaded.getHospitalName())
            .as("the @PrePersist snapshot keeps the hospital after the assignment is gone")
            .isEqualTo("Dangling Hospital");
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
    }
}
