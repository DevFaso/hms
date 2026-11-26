package com.example.hms.upload;

import com.example.hms.repository.UploadRecordRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeResolver;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much a caller may upload: a rate per minute, and an allowance of files
 * and of bytes over the last 24 hours, for the user and for the hospital they
 * act at. A patient account has its own, smaller allowance.
 *
 * <p>The allowance is counted from the stored uploads
 * ({@link com.example.hms.model.UploadRecord}), so it holds across restarts
 * and instances; the rate is held in memory, per instance. Every value is a
 * property under {@code app.upload} (see {@code application.properties}).
 */
@Slf4j
@Service
public class UploadLimiter {

    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int MAX_TRACKED_CALLERS = 20_000;
    private static final Duration IDLE_BUCKET = Duration.ofMinutes(10);
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

    private final UploadRecordRepository records;
    private final boolean enabled;
    private final int perMinute;
    private final Allowance user;
    private final Allowance patient;
    private final Allowance hospital;
    private final Map<String, TimedBucket> buckets = new ConcurrentHashMap<>();

    /** A count of files and a count of bytes over the window. */
    record Allowance(long files, long bytes) {
    }

    private static final class TimedBucket {
        private final Bucket bucket;
        private volatile Instant lastUsed = Instant.now();

        private TimedBucket(Bucket bucket) {
            this.bucket = bucket;
        }
    }

    @SuppressWarnings("java:S107") // one constructor parameter per configured limit
    public UploadLimiter(
            UploadRecordRepository records,
            @Value("${app.upload.limits.enabled:true}") boolean enabled,
            @Value("${app.upload.rate.per-minute:30}") int perMinute,
            @Value("${app.upload.quota.user.files-per-day:300}") long userFiles,
            @Value("${app.upload.quota.user.bytes-per-day:1GB}") String userBytes,
            @Value("${app.upload.quota.patient.files-per-day:40}") long patientFiles,
            @Value("${app.upload.quota.patient.bytes-per-day:200MB}") String patientBytes,
            @Value("${app.upload.quota.hospital.files-per-day:10000}") long hospitalFiles,
            @Value("${app.upload.quota.hospital.bytes-per-day:50GB}") String hospitalBytes) {
        this.records = records;
        this.enabled = enabled;
        this.perMinute = Math.max(1, perMinute);
        this.user = new Allowance(userFiles, DataSize.parse(userBytes).toBytes());
        this.patient = new Allowance(patientFiles, DataSize.parse(patientBytes).toBytes());
        this.hospital = new Allowance(hospitalFiles, DataSize.parse(hospitalBytes).toBytes());
    }

    /**
     * Admit {@code files} files of {@code bytes} bytes in all from the current
     * caller, or refuse them.
     *
     * @throws UploadLimitExceededException over the rate or an allowance
     */
    public void admit(int files, long bytes) {
        if (!enabled || files <= 0) {
            return;
        }
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = context.getPrincipalUserId();
        String caller = userId != null ? userId.toString()
            : authentication != null ? authentication.getName() : null;
        if (caller != null && !bucketOf(caller).tryConsume(Math.min(files, perMinute))) {
            log.warn("[UPLOAD] rate refused for a caller ({} per minute)", perMinute);
            throw new UploadLimitExceededException("file.upload.rateLimited", 60);
        }
        LocalDateTime since = LocalDateTime.now().minus(WINDOW);
        if (userId != null) {
            Allowance allowance = isPatientOnly(authentication) ? patient : user;
            require(allowance, records.countByUserSince(userId, since), records.bytesByUserSince(userId, since),
                files, bytes, "file.upload.quota.userFiles", "file.upload.quota.userBytes");
        }
        UUID hospitalId = ActingScopeResolver.pinnedHospitalIdOf(context);
        if (hospitalId != null) {
            require(hospital, records.countByHospitalSince(hospitalId, since),
                records.bytesByHospitalSince(hospitalId, since),
                files, bytes, "file.upload.quota.hospitalFiles", "file.upload.quota.hospitalBytes");
        }
    }

    private static void require(Allowance allowance, long usedFiles, long usedBytes, int files, long bytes,
                                String filesKey, String bytesKey) {
        if (usedFiles + files > allowance.files()) {
            log.warn("[UPLOAD] daily file allowance refused ({})", filesKey);
            throw new UploadLimitExceededException(filesKey, 0, allowance.files());
        }
        if (usedBytes + bytes > allowance.bytes()) {
            log.warn("[UPLOAD] daily size allowance refused ({})", bytesKey);
            throw new UploadLimitExceededException(bytesKey, 0, allowance.bytes() / BYTES_PER_MEGABYTE);
        }
    }

    /** An account that holds the patient role and no other. */
    private static boolean isPatientOnly(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().isEmpty()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (!"ROLE_PATIENT".equals(authority.getAuthority())) {
                return false;
            }
        }
        return true;
    }

    private Bucket bucketOf(String caller) {
        if (buckets.size() > MAX_TRACKED_CALLERS) {
            Instant idleSince = Instant.now().minus(IDLE_BUCKET);
            buckets.values().removeIf(held -> held.lastUsed.isBefore(idleSince));
        }
        TimedBucket held = buckets.computeIfAbsent(caller, ignored -> new TimedBucket(Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)).build())
            .build()));
        held.lastUsed = Instant.now();
        return held.bucket;
    }
}
