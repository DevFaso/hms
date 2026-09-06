package com.example.hms.config;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code @Scheduled} method either holds a {@link SchedulerLock} or is
 * named below as per-instance work with the reason. A sweep added without
 * either fails here, which is the whole point: "no ShedLock anywhere" was
 * the state of 26 jobs before V155, and nothing said so.
 */
@DisplayName("Scheduler lock coverage")
class SchedulerLockCoverageTest {

    /**
     * Jobs that must run on EVERY instance because they maintain state that
     * lives in that instance's memory. Locking them would leave the losing
     * instances' caches uncleaned.
     */
    private static final Map<String, String> PER_INSTANCE = Map.of(
        "InMemoryTokenBlacklistService.evictExpired", "local blacklist map",
        "InMemoryIdleSessionTracker.evictExpired", "local idle-session map",
        "RateLimitFilter.evictStaleBuckets", "local rate-limit buckets",
        "WsTicketService.evictExpired", "local WebSocket ticket map",
        "DowntimeStateService.refresh", "polls the DB flag into this instance's cache",
        "GlobalSessionRevocationService.refresh", "polls the global min-iat into this instance's cache"
    );

    /**
     * Jobs whose lock lives on the service method they delegate to, because a
     * manual endpoint calls that same method: the sweep and the manual run
     * then contend for ONE lock. A lock on the scheduler method as well would
     * block its own delegate (ShedLock skips a method whose lock is held).
     */
    private static final Map<String, String> LOCKED_VIA_DELEGATE = Map.of(
        "CriticalValueEscalationScheduler.runSweep", "com.example.hms.service.CriticalValueNotificationService#escalateOverdue",
        "ImagingCriticalEscalationScheduler.runSweep", "com.example.hms.service.ImagingCriticalNotificationService#escalateOverdue"
    );

    @Test
    @DisplayName("a job locked through its delegate has the lock there, and only there")
    void delegateLocksAreReal() throws Exception {
        for (Map.Entry<String, String> entry : LOCKED_VIA_DELEGATE.entrySet()) {
            String[] target = entry.getValue().split("#");
            Method delegate = Class.forName(target[0]).getMethod(target[1]);
            assertThat(AnnotatedElementUtils.findMergedAnnotation(delegate, SchedulerLock.class))
                .as(entry.getKey() + " delegates to a locked " + entry.getValue()).isNotNull();
        }
    }

    /**
     * Every {@code @SchedulerLock} anywhere — on schedulers or on the service
     * methods they delegate to — must be skippable: ShedLock answers a skipped
     * run with null, so a primitive return type throws
     * LockingNotSupportedException on EVERY call (prod, 2026-09-05). Names must
     * be unique across both kinds, or two unrelated jobs silently share a lock.
     */
    @Test
    @DisplayName("no locked method returns a primitive, and lock names are unique everywhere")
    void everyLockIsSkippableAndUniquelyNamed() {
        Set<String> primitives = new TreeSet<>();
        Set<String> names = new HashSet<>();
        Set<String> duplicates = new TreeSet<>();
        for (Class<?> type : lockedBeans()) {
            for (Method method : type.getDeclaredMethods()) {
                SchedulerLock lock = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class);
                if (lock == null) {
                    continue;
                }
                Class<?> returns = method.getReturnType();
                if (returns.isPrimitive() && returns != void.class) {
                    primitives.add(type.getSimpleName() + "." + method.getName() + " -> " + returns.getSimpleName());
                }
                if (!names.add(lock.name())) {
                    duplicates.add(lock.name());
                }
            }
        }
        assertThat(primitives).as("box the return type (Integer, not int) so ShedLock can return null for a skipped run").isEmpty();
        assertThat(duplicates).as("lock names shared by two methods").isEmpty();
    }

    @Test
    @DisplayName("the lock advisor is ordered outside the transaction advisor")
    void lockAdvisorWrapsTheTransaction() {
        EnableSchedulerLock enable = SchedulerLockConfig.class.getAnnotation(EnableSchedulerLock.class);
        assertThat(enable).isNotNull();
        assertThat(enable.order())
            .as("lock must be acquired before the transaction opens and released after it commits")
            .isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    @DisplayName("every scheduled job is locked, or named as per-instance with a reason")
    void everyScheduledJobIsLockedOrPerInstance() {
        Set<String> unlocked = new TreeSet<>();
        Set<String> seenNames = new HashSet<>();
        Set<String> badDurations = new TreeSet<>();
        int locked = 0;
        for (Class<?> bean : scheduledBeans()) {
            for (Method method : bean.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)) {
                    continue;
                }
                String key = bean.getSimpleName() + "." + method.getName();
                SchedulerLock lock = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class);
                if (lock == null) {
                    if (!PER_INSTANCE.containsKey(key) && !LOCKED_VIA_DELEGATE.containsKey(key)) {
                        unlocked.add(key);
                    }
                    continue;
                }
                assertThat(LOCKED_VIA_DELEGATE).as(key + " must not lock twice").doesNotContainKey(key);
                locked++;
                assertThat(seenNames.add(lock.name())).as("duplicate lock name " + lock.name()).isTrue();
                assertThat(lock.name()).hasSizeLessThanOrEqualTo(64);
                Duration atMost = Duration.parse(lock.lockAtMostFor());
                Duration atLeast = lock.lockAtLeastFor().isBlank() ? Duration.ZERO : Duration.parse(lock.lockAtLeastFor());
                if (atLeast.compareTo(atMost) > 0 || atMost.isZero()) {
                    badDurations.add(key);
                }
            }
        }
        assertThat(unlocked)
            .withFailMessage("""
                These @Scheduled jobs have no @SchedulerLock and are not listed as per-instance                 work, so two instances will run them at once:
                %s
                Add @SchedulerLock(name = "<Class>.<method>", lockAtMostFor = "...",                 lockAtLeastFor = "PT5S"), or add them to PER_INSTANCE with the reason.""",
                String.join("\n", unlocked))
            .isEmpty();
        assertThat(badDurations).as("lockAtLeastFor must not exceed lockAtMostFor").isEmpty();
        assertThat(locked).isGreaterThanOrEqualTo(18);
    }

    @Test
    @DisplayName("the per-instance list names only jobs that exist")
    void perInstanceListIsCurrent() {
        Set<String> existing = new HashSet<>();
        for (Class<?> bean : scheduledBeans()) {
            for (Method method : bean.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)) {
                    existing.add(bean.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertThat(existing).containsAll(PER_INSTANCE.keySet()).containsAll(LOCKED_VIA_DELEGATE.keySet());
    }

    /**
     * Metadata scan of every class under {@code com.example.hms}, NOT a
     * component scan: {@code ClassPathScanningCandidateComponentProvider}
     * evaluates {@code @ConditionalOnProperty} / {@code @Profile} against the
     * test environment and silently drops the jobs that are off by default —
     * exactly the ones most likely to be forgotten.
     */
    /** Every class with at least one {@code @SchedulerLock} method (schedulers and delegates alike). */
    private static List<Class<?>> lockedBeans() {
        return classesWithAnnotatedMethods(SchedulerLock.class.getName());
    }

    private static List<Class<?>> scheduledBeans() {
        return classesWithAnnotatedMethods(Scheduled.class.getName());
    }

    private static List<Class<?>> classesWithAnnotatedMethods(String annotationName) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> found = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:com/example/hms/**/*.class")) {
                MetadataReader reader = factory.getMetadataReader(resource);
                String className = reader.getClassMetadata().getClassName();
                if (className.endsWith("Test") || className.contains("$")) {
                    continue;
                }
                if (reader.getAnnotationMetadata().hasAnnotatedMethods(annotationName)) {
                    found.add(Class.forName(className));
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return found;
    }
}
