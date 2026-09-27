package com.example.hms.service;

import com.example.hms.model.User;
import com.example.hms.repository.EmailChangeRequestRepository;
import com.example.hms.repository.PasswordResetTokenRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailChangeWritesTest {

    @Mock private EmailChangeRequestRepository requestRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository resetTokenRepository;
    @InjectMocks private EmailChangeWrites writes;

    private final UUID userId = UUID.randomUUID();

    @Test
    void applyingTheEmailEndsEveryUnconsumedResetLinkAfterTheWrite() {
        User user = new User();
        user.setId(userId);
        user.setEmail("old@example.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        writes.applyEmail(userId, "new@example.com");

        assertThat(user.getEmail()).isEqualTo("new@example.com");
        InOrder order = inOrder(userRepository, resetTokenRepository);
        order.verify(userRepository).saveAndFlush(user);
        order.verify(resetTokenRepository).deleteByUser_IdAndConsumedAtIsNull(userId);
    }

    @Test
    void aRefusedWriteTouchesNoResetLink() {
        User user = new User();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_user_email"));

        assertThatThrownBy(() -> writes.applyEmail(userId, "taken@example.com"))
            .isInstanceOf(DataIntegrityViolationException.class);
        verify(resetTokenRepository, never()).deleteByUser_IdAndConsumedAtIsNull(any());
    }
}
