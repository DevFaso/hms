package com.example.hms.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * One instance runs each scheduled sweep.
 *
 * <p>Every {@code @Scheduled} method that touches the database carries
 * {@code @SchedulerLock}; ShedLock takes a row in {@code platform.shedlock}
 * (V155) before the method runs and other instances skip it until
 * {@code lockAtMostFor} elapses. Per-instance housekeeping — evicting a
 * local token blacklist, a rate-limit bucket map, an idle-session tracker,
 * refreshing a cached downtime flag — must run on every instance and is
 * deliberately unlocked; {@code SchedulerLockCoverageTest} keeps the two
 * lists explicit.
 *
 * <p>{@code usingDbTime()}: the lock timestamps come from the database
 * clock, so instances with drifting clocks agree on who holds a lock.
 * {@code defaultLockAtMostFor} is a safety net for a job that dies holding
 * its lock; each annotation sets its own, sized to the job.
 *
 * <p>{@code order}: the lock advisor must sit OUTSIDE the transaction advisor
 * (both default to lowest precedence, which leaves the nesting to bean
 * registration order). With the lock outer it is acquired before the
 * transaction opens and released after it commits — so a second instance
 * cannot take the lock while the first run's stamps are still uncommitted
 * and escalate the same rows again. {@code SchedulerLockCoverageTest} pins
 * the value.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M", order = Ordered.LOWEST_PRECEDENCE - 1)
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("platform.shedlock")
                .usingDbTime()
                .build());
    }
}
