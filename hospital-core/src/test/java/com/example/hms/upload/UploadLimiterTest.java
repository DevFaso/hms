package com.example.hms.upload;

import com.example.hms.repository.UploadRecordRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Uploads are limited per user and per hospital, by count, by bytes and by rate")
class UploadLimiterTest {

    private static final long MB = 1024L * 1024L;

    private final UploadRecordRepository records = mock(UploadRecordRepository.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    /** 10 a minute; a user 5 files / 20 MB, a patient 2 files / 4 MB, a hospital 8 files / 30 MB a day. */
    private UploadLimiter limiter(boolean enabled) {
        return new UploadLimiter(records, enabled, 10, 5, "20MB", 2, "4MB", 8, "30MB", java.time.Clock.systemDefaultZone());
    }

    @BeforeEach
    void signInAsStaff() {
        actAs("ROLE_NURSE", hospitalId);
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void actAs(String role, UUID activeHospital) {
        actAs(activeHospital, role);
    }

    private void actAs(UUID activeHospital, String... roles) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(userId).principalUsername("m16").activeHospitalId(activeHospital).build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "m16", "unused", java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private void refused(UploadLimiter limiter, int files, long bytes, String key) {
        assertThatThrownBy(() -> limiter.admit(files, bytes))
            .isInstanceOf(UploadLimitExceededException.class)
            .hasFieldOrPropertyWithValue("messageKey", key);
    }

    @Test
    @DisplayName("a user under every allowance uploads")
    void underTheAllowance() {
        when(records.countByUserSince(eq(userId), any())).thenReturn(4L);
        when(records.bytesByUserSince(eq(userId), any())).thenReturn(10 * MB);

        assertThatCode(() -> limiter(true).admit(1, 10 * MB)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the file that would pass the user's daily count is refused")
    void userFileCount() {
        when(records.countByUserSince(eq(userId), any())).thenReturn(5L);

        refused(limiter(true), 1, 1, "file.upload.quota.userFiles");
    }

    @Test
    @DisplayName("the file that would pass the user's daily bytes is refused")
    void userBytes() {
        when(records.bytesByUserSince(eq(userId), any())).thenReturn(15 * MB);

        refused(limiter(true), 1, 5 * MB + 1, "file.upload.quota.userBytes");
    }

    @Test
    @DisplayName("the hospital's daily count and bytes hold whoever sends the file")
    void hospitalAllowance() {
        when(records.countByHospitalSince(eq(hospitalId), any())).thenReturn(8L);
        refused(limiter(true), 1, 1, "file.upload.quota.hospitalFiles");

        when(records.countByHospitalSince(eq(hospitalId), any())).thenReturn(0L);
        when(records.bytesByHospitalSince(eq(hospitalId), any())).thenReturn(30 * MB);
        refused(limiter(true), 1, 1, "file.upload.quota.hospitalBytes");
    }

    @Test
    @DisplayName("a patient account has its own, smaller allowance")
    void patientAllowance() {
        actAs("ROLE_PATIENT", null);
        when(records.countByUserSince(eq(userId), any())).thenReturn(2L);
        refused(limiter(true), 1, 1, "file.upload.quota.userFiles");

        when(records.countByUserSince(eq(userId), any())).thenReturn(0L);
        refused(limiter(true), 1, 4 * MB + 1, "file.upload.quota.userBytes");
    }

    @Test
    @DisplayName("a patient signed in through Keycloak keeps the patient allowance: its stock realm roles are not HMS roles")
    void patientWithKeycloakDefaultRoles() {
        actAs(null, "ROLE_PATIENT", "ROLE_OFFLINE_ACCESS", "ROLE_UMA_AUTHORIZATION", "ROLE_DEFAULT-ROLES-HMS");
        when(records.countByUserSince(eq(userId), any())).thenReturn(2L);
        refused(limiter(true), 1, 1, "file.upload.quota.userFiles");

        when(records.countByUserSince(eq(userId), any())).thenReturn(0L);
        refused(limiter(true), 1, 4 * MB + 1, "file.upload.quota.userBytes");
    }

    @Test
    @DisplayName("staff keep the user allowance, with or without the patient role or Keycloak's stock roles")
    void staffKeepTheUserAllowance() {
        when(records.countByUserSince(eq(userId), any())).thenReturn(4L);
        when(records.bytesByUserSince(eq(userId), any())).thenReturn(10 * MB);
        for (String[] roles : new String[][] {
            {"ROLE_NURSE", "ROLE_OFFLINE_ACCESS", "ROLE_UMA_AUTHORIZATION", "ROLE_DEFAULT-ROLES-HMS"},
            {"ROLE_PATIENT", "ROLE_DOCTOR", "ROLE_OFFLINE_ACCESS"},
            {"ROLE_OFFLINE_ACCESS"}}) {
            actAs(hospitalId, roles);
            assertThatCode(() -> limiter(true).admit(1, 10 * MB)).as("%s", (Object) roles)
                .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a burst over the rate is refused with a moment to try again")
    void rate() {
        UploadLimiter limiter = limiter(true);
        for (int i = 0; i < 10; i++) {
            limiter.admit(1, 1);
        }

        assertThatThrownBy(() -> limiter.admit(1, 1))
            .isInstanceOf(UploadLimitExceededException.class)
            .hasFieldOrPropertyWithValue("messageKey", "file.upload.rateLimited")
            .hasFieldOrPropertyWithValue("retryAfterSeconds", 60L);
    }

    @Test
    @DisplayName("one caller's burst does not spend another's rate")
    void rateIsPerCaller() {
        UploadLimiter limiter = limiter(true);
        for (int i = 0; i < 10; i++) {
            limiter.admit(1, 1);
        }
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID()).activeHospitalId(hospitalId).build());

        assertThatCode(() -> limiter.admit(1, 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("switched off, nothing is counted")
    void disabled() {
        UploadLimiter limiter = limiter(false);
        for (int i = 0; i < 50; i++) {
            limiter.admit(1, 100 * MB);
        }
        verifyNoInteractions(records);
    }
}
