package com.example.hms.service;

import com.example.hms.enums.MfaMethodType;
import com.example.hms.model.MfaBackupCode;
import com.example.hms.model.User;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    @Mock private UserMfaEnrollmentRepository enrollmentRepository;
    @Mock private MfaBackupCodeRepository backupCodeRepository;

    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private MfaService mfaService;

    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        mfaService = new MfaService(enrollmentRepository, backupCodeRepository, passwordEncoder);
        userId = UUID.randomUUID();
        testUser = User.builder().username("test@example.com").build();
        ReflectionTestUtils.setField(testUser, "id", userId);
    }

    @Test
    void enrollTotp_createsNewEnrollmentAndBackupCodes() {
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.empty());
        when(enrollmentRepository.save(any(UserMfaEnrollment.class)))
                .thenAnswer(inv -> {
                    UserMfaEnrollment e = inv.getArgument(0);
                    ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
                    return e;
                });
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser);

        assertThat(result.secret()).isNotBlank();
        assertThat(result.otpauthUri()).contains("otpauth://totp/").contains("issuer=HMS");
        assertThat(result.backupCodes()).hasSize(10);
        assertThat(result.backupCodes()).allMatch(c -> c.length() == 8);

        verify(enrollmentRepository).save(any(UserMfaEnrollment.class));
        verify(backupCodeRepository, times(10)).save(any(MfaBackupCode.class));
    }

    @Test
    void enrollTotp_resetsExistingUnverifiedEnrollment() {
        UserMfaEnrollment existing = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(false).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(existing));
        when(enrollmentRepository.save(any(UserMfaEnrollment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser);

        assertThat(result.secret()).isNotBlank();
        verify(backupCodeRepository).deleteAllByUserId(userId);
    }

    @Test
    void enrollTotp_resetsAlreadyVerifiedEnrollment() {
        UserMfaEnrollment existing = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(true).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(existing));
        when(enrollmentRepository.save(any(UserMfaEnrollment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser);

        assertThat(result.secret()).isNotBlank();
        assertThat(existing.isVerified()).isFalse();
        assertThat(existing.isEnabled()).isFalse();
    }

    @Test
    void isMfaEnabled_returnsTrueWhenVerifiedAndEnabled() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(true).build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.isMfaEnabled(userId)).isTrue();
    }

    @Test
    void isMfaEnabled_returnsFalseWhenNoEnrollment() {
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.empty());

        assertThat(mfaService.isMfaEnabled(userId)).isFalse();
    }

    @Test
    void isMfaEnabled_returnsFalseWhenNotVerified() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(false).enabled(true).build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.isMfaEnabled(userId)).isFalse();
    }

    @Test
    void verifyBackupCode_consumesMatchingCode() {
        String rawCode = "12345678";
        String hashed = passwordEncoder.encode(rawCode);

        MfaBackupCode code = MfaBackupCode.builder()
                .userId(userId).codeHash(hashed).used(false).build();
        ReflectionTestUtils.setField(code, "id", UUID.randomUUID());

        when(backupCodeRepository.findByUserIdAndUsedFalse(userId))
                .thenReturn(List.of(code));
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        boolean result = mfaService.verifyBackupCode(userId, rawCode);

        assertThat(result).isTrue();
        assertThat(code.isUsed()).isTrue();
        assertThat(code.getUsedAt()).isNotNull();
        verify(backupCodeRepository).save(code);
    }

    @Test
    void verifyBackupCode_returnsFalseForInvalidCode() {
        MfaBackupCode code = MfaBackupCode.builder()
                .userId(userId).codeHash(passwordEncoder.encode("99999999")).used(false).build();

        when(backupCodeRepository.findByUserIdAndUsedFalse(userId))
                .thenReturn(List.of(code));

        boolean result = mfaService.verifyBackupCode(userId, "00000000");

        assertThat(result).isFalse();
        verify(backupCodeRepository, never()).save(any());
    }

    @Test
    void isMfaRequiredForRole_matchesConfiguredRoles() {
        List<String> required = List.of("ROLE_DOCTOR", "ROLE_ADMIN");
        assertThat(mfaService.isMfaRequiredForRole("ROLE_DOCTOR", required)).isTrue();
        assertThat(mfaService.isMfaRequiredForRole("ROLE_NURSE", required)).isFalse();
    }

    @Test
    void regenerateBackupCodes_deletesOldAndCreatesNew() {
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<String> codes = mfaService.regenerateBackupCodes(userId);

        assertThat(codes).hasSize(10);
        verify(backupCodeRepository).deleteAllByUserId(userId);
        verify(backupCodeRepository, times(10)).save(any(MfaBackupCode.class));
    }
}
