package com.example.hms.config;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
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
                    if (!PER_INSTANCE.containsKey(key)) {
                        unlocked.add(key);
                    }
                    continue;
                }
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
        assertThat(locked).isGreaterThanOrEqualTo(19);
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
        assertThat(existing).containsAll(PER_INSTANCE.keySet());
    }

    /**
     * Metadata scan of every class under {@code com.example.hms}, NOT a
     * component scan: {@code ClassPathScanningCandidateComponentProvider}
     * evaluates {@code @ConditionalOnProperty} / {@code @Profile} against the
     * test environment and silently drops the jobs that are off by default —
     * exactly the ones most likely to be forgotten.
     */
    private static List<Class<?>> scheduledBeans() {
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
                if (reader.getAnnotationMetadata().hasAnnotatedMethods(Scheduled.class.getName())) {
                    found.add(Class.forName(className));
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return found;
    }
}
