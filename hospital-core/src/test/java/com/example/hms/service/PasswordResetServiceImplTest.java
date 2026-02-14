package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.PasswordResetToken;
import com.example.hms.model.User;
import com.example.hms.repository.PasswordResetTokenRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Locale;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private EmailService emailService;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private PasswordResetServiceImpl service;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setEmail("test@test.com");
        user.setUsername("testuser");
    }

    @Test
    void requestReset_success() {
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestReset("test@test.com", Locale.ENGLISH);

        verify(tokenRepository).deleteByUser_IdAndConsumedAtIsNull(userId);
        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(emailService).sendPasswordResetEmail(eq("test@test.com"), anyString());
    }

    @Test
    void requestReset_withIp_success() {
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestReset("test@test.com", Locale.ENGLISH, "127.0.0.1");

        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(emailService).sendPasswordResetEmail(eq("test@test.com"), anyString());
    }

    @Test
    void requestReset_userNotFound() {
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestReset("unknown@test.com", Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirmReset_success() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(rawToken.toLowerCase())
            .expiration(LocalDateTime.now().plusHours(1))
            .build();

        when(tokenRepository.findByTokenHash(rawToken.toLowerCase())).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("newPassword")).thenReturn("encodedPwd");

        service.confirmReset(rawToken, "newPassword");

        verify(userRepository).save(user);
        verify(tokenRepository).save(resetToken);
        assertThat(user.getPasswordHash()).isEqualTo("encodedPwd");
        assertThat(resetToken.getConsumedAt()).isNotNull();
    }

    @Test
    void confirmReset_invalidToken() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        when(tokenRepository.findByTokenHash(rawToken.toLowerCase())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmReset(rawToken, "newPwd"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirmReset_expiredToken() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(rawToken.toLowerCase())
            .expiration(LocalDateTime.now().minusHours(1))
            .build();

        when(tokenRepository.findByTokenHash(rawToken.toLowerCase())).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> service.confirmReset(rawToken, "newPwd"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void verifyToken_valid() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(rawToken.toLowerCase())
            .expiration(LocalDateTime.now().plusHours(1))
            .build();

        when(tokenRepository.findByTokenHash(rawToken.toLowerCase())).thenReturn(Optional.of(resetToken));
        assertThat(service.verifyToken(rawToken)).isTrue();
    }

    @Test
    void verifyToken_notFound() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        when(tokenRepository.findByTokenHash(rawToken.toLowerCase())).thenReturn(Optional.empty());
        assertThat(service.verifyToken(rawToken)).isFalse();
    }

    @Test
    void cleanupExpiredTokens_returnsCount() {
        when(tokenRepository.deleteByExpirationBefore(any(LocalDateTime.class))).thenReturn(5L);
        int result = service.cleanupExpiredTokens();
        assertThat(result).isEqualTo(5);
    }

    @Test
    void invalidateAllForUser_success() {
        service.invalidateAllForUser(userId);
        verify(tokenRepository).deleteByUser_IdAndConsumedAtIsNull(userId);
    }
}
