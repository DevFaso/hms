package com.example.hms.repository;

import com.example.hms.model.platform.SubscriptionPlan;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D850: deactivating a plan and assigning it serialize on the plan row.
 * The finder both paths use must take a write lock, and must run.
 */
@DataJpaTest
@ActiveProfiles("test")
class SubscriptionPlanRepositoryLockTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private SubscriptionPlanRepository repository;

    @Test
    void findByIdForUpdateTakesAPessimisticWriteLock() throws NoSuchMethodException {
        Lock lock = SubscriptionPlanRepository.class
            .getDeclaredMethod("findByIdForUpdate", UUID.class)
            .getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void findByIdForUpdateLoadsThePlanUnderTheLock() {
        SubscriptionPlan plan = entityManager.persistFlushFind(SubscriptionPlan.builder()
            .name("Essentiel")
            .tierCode("ESS-LOCK")
            .description("d")
            .monthlyPriceCents(500_000L)
            .currency("XOF")
            .includedSeats(5)
            .featureKeys("")
            .active(true)
            .build());
        entityManager.clear();

        assertThat(repository.findByIdForUpdate(plan.getId()))
            .get()
            .satisfies(found -> {
                assertThat(found.getTierCode()).isEqualTo("ESS-LOCK");
                assertThat(entityManager.getEntityManager().getLockMode(found))
                    .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
            });
        assertThat(repository.findByIdForUpdate(UUID.randomUUID())).isEmpty();
    }
}
