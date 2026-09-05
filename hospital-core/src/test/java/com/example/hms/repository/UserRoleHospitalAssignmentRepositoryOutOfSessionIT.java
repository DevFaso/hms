package com.example.hms.repository;

import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reproduces the write-audit failure seen on dev on 2026-09-05: the audit
 * interceptor looks the actor's assignment up in {@code afterCompletion},
 * after the request's persistence context is closed, then reads
 * {@code assignment.getHospital().getName()}. With the plain finder that is a
 * lazy proxy with no session behind it, so the read threw
 * {@link LazyInitializationException} and the whole audit row was dropped.
 *
 * <p>The test runs with {@code NOT_SUPPORTED} so the repository call opens and
 * closes its own transaction, exactly like a call from an interceptor: the
 * fixtures are committed first, the finder is called outside any transaction,
 * and the hospital is touched afterwards.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TenantContextAccessor.class, EncryptionKeyHolder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserRoleHospitalAssignmentRepositoryOutOfSessionIT {

    private static final String HOSPITAL_NAME = "Write Audit Hospital";
    private static final String ROLE_NAME = "ROLE_WRITE_AUDIT_NURSE";

    @Autowired
    private UserRoleHospitalAssignmentRepository repository;
    @Autowired
    private TestEntityManager em;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate committed;
    private UUID userId;
    private UUID hospitalId;
    private UUID organizationId;
    private UUID roleId;

    @BeforeEach
    void seedInACommittedTransaction() {
        committed = new TransactionTemplate(transactionManager);
        committed.executeWithoutResult(status -> {
            Organization organization = em.persist(Organization.builder()
                .name("Write Audit Org")
                .code("ORG-WA1")
                .type(OrganizationType.HOSPITAL_CHAIN)
                .build());
            Hospital hospital = em.persist(Hospital.builder()
                .name(HOSPITAL_NAME)
                .code("HOSP-WA1")
                .address("1 Rue de l'Audit")
                .city("Ouagadougou")
                .country("BF")
                .organization(organization)
                .build());
            User user = em.persist(User.builder()
                .username("wa_nurse_1")
                .passwordHash("h")
                .email("wa_nurse_1@example.com")
                .phoneNumber("+22670000901")
                .firstName("Awa")
                .lastName("Nurse")
                .build());
            Role role = em.persist(Role.builder()
                .name(ROLE_NAME)
                .code(ROLE_NAME)
                .build());
            em.persist(UserRoleHospitalAssignment.builder()
                .user(user)
                .role(role)
                .hospital(hospital)
                .assignedAt(LocalDateTime.now())
                .active(true)
                .build());
            organizationId = organization.getId();
            hospitalId = hospital.getId();
            userId = user.getId();
            roleId = role.getId();
        });
    }

    @AfterEach
    void removeWhatWasCommitted() {
        // NOT_SUPPORTED means nothing rolls back for us: leave the shared H2
        // context as we found it for the other data-JPA tests.
        committed.executeWithoutResult(status -> {
            var entityManager = em.getEntityManager();
            entityManager.createQuery("delete from UserRoleHospitalAssignment a where a.user.id = :userId")
                .setParameter("userId", userId).executeUpdate();
            entityManager.createQuery("delete from User u where u.id = :userId")
                .setParameter("userId", userId).executeUpdate();
            entityManager.createQuery("delete from Role r where r.id = :roleId")
                .setParameter("roleId", roleId).executeUpdate();
            entityManager.createQuery("delete from Hospital h where h.id = :hospitalId")
                .setParameter("hospitalId", hospitalId).executeUpdate();
            entityManager.createQuery("delete from Organization o where o.id = :organizationId")
                .setParameter("organizationId", organizationId).executeUpdate();
        });
    }

    @Test
    void graphFinderHandsBackAHospitalThatSurvivesTheEndOfItsSession() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
            .as("the finder must open and close its own session, like a call from afterCompletion")
            .isFalse();

        UserRoleHospitalAssignment found = repository
            .findFirstWithHospitalAndRoleByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId)
            .orElseThrow();

        assertThat(Hibernate.isInitialized(found.getHospital())).isTrue();
        assertThat(found.getHospital().getName()).isEqualTo(HOSPITAL_NAME);
        assertThat(found.getRole().getName()).isEqualTo(ROLE_NAME);
    }

    @Test
    void plainFinderLeavesADeadHospitalProxyWhichIsWhyTheGraphFinderExists() {
        UserRoleHospitalAssignment found = repository
            .findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId)
            .orElseThrow();
        Hospital proxy = found.getHospital();

        assertThat(Hibernate.isInitialized(proxy)).isFalse();
        assertThatThrownBy(proxy::getName).isInstanceOf(LazyInitializationException.class);
    }
}
