package com.example.hms.service;

import com.example.hms.mapper.ChatMessageMapper;
import com.example.hms.model.ChatMessage;
import com.example.hms.model.User;
import com.example.hms.payload.dto.ChatMessageRequestDTO;
import com.example.hms.payload.dto.ChatMessageResponseDTO;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ChatMessageServiceImplTest {
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private UserRepository userRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRoleHospitalAssignmentRepository userRoleHospitalAssignmentRepository;
    @Mock private ChatMessageMapper chatMessageMapper;
    @Mock private MessageSource messageSource;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private ChatMessageServiceImpl chatMessageService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void sendMessage_shouldThrowIfRecipientIsSender() {
        UUID senderId = UUID.randomUUID();
        User sender = new User();
        sender.setId(senderId);
        sender.setEmail("test@example.com");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(sender));
        ChatMessageRequestDTO dto = new ChatMessageRequestDTO();
        dto.setRecipientEmail("test@example.com");
        dto.setHospitalName("General Hospital");
        dto.setRoleCode("ROLE_RECEPTIONIST");
        dto.setContent("Hello!");
        // Simulate current user
        ChatMessageServiceImpl service = spy(chatMessageService);
        doReturn(senderId).when(service).getCurrentUserId();
        assertThrows(SecurityException.class, () -> service.sendMessage(dto, Locale.ENGLISH));
    }

    @Test
    void sendMessage_shouldThrowIfRecipientNotFound() {
        UUID senderId = UUID.randomUUID();
        User sender = new User();
        sender.setId(senderId);
        sender.setEmail("sender@example.com");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.empty());
        ChatMessageRequestDTO dto = new ChatMessageRequestDTO();
        dto.setRecipientEmail("recipient@example.com");
        dto.setHospitalName("General Hospital");
        dto.setRoleCode("ROLE_RECEPTIONIST");
        dto.setContent("Hello!");
        ChatMessageServiceImpl service = spy(chatMessageService);
        doReturn(senderId).when(service).getCurrentUserId();
        assertThrows(com.example.hms.exception.ResourceNotFoundException.class, () -> service.sendMessage(dto, Locale.ENGLISH));
    }

    @Test
    void sendMessage_shouldThrowIfHospitalNotFound() {
        UUID senderId = UUID.randomUUID();
        User sender = new User();
        sender.setId(senderId);
        sender.setEmail("sender@example.com");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(new User()));
        when(hospitalRepository.findByNameIgnoreCase("General Hospital")).thenReturn(Optional.empty());
        ChatMessageRequestDTO dto = new ChatMessageRequestDTO();
        dto.setRecipientEmail("recipient@example.com");
        dto.setHospitalName("General Hospital");
        dto.setRoleCode("ROLE_RECEPTIONIST");
        dto.setContent("Hello!");
        ChatMessageServiceImpl service = spy(chatMessageService);
        doReturn(senderId).when(service).getCurrentUserId();
        assertThrows(com.example.hms.exception.ResourceNotFoundException.class, () -> service.sendMessage(dto, Locale.ENGLISH));
    }

    @Test
    void sendMessage_noHospitalContext_succeedsWhenSenderHasActiveAssignment() {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        User sender = new User();
        sender.setId(senderId);
        User recipient = new User();
        recipient.setId(recipientId);

        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
        when(userRoleHospitalAssignmentRepository.existsByUserIdAndActiveTrue(senderId)).thenReturn(true);

        ChatMessage savedMessage = new ChatMessage();
        savedMessage.setSender(sender);
        savedMessage.setRecipient(recipient);
        savedMessage.setContent("testing within hospital");
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(savedMessage);
        when(chatMessageMapper.toChatMessageResponseDTO(any(ChatMessage.class)))
            .thenReturn(ChatMessageResponseDTO.builder().build());

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("testing within hospital")
            .build();

        ChatMessageServiceImpl service = spy(chatMessageService);
        doReturn(senderId).when(service).getCurrentUserId();

        ChatMessageResponseDTO result = service.sendMessage(dto, Locale.ENGLISH);
        assertNotNull(result);
    }

    @Test
    void sendMessage_noHospitalContext_throwsWhenSenderHasNoActiveAssignment() {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        User sender = new User();
        sender.setId(senderId);
        User recipient = new User();
        recipient.setId(recipientId);

        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
        when(userRoleHospitalAssignmentRepository.existsByUserIdAndActiveTrue(senderId)).thenReturn(false);

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("testing within hospital")
            .build();

        ChatMessageServiceImpl service = spy(chatMessageService);
        doReturn(senderId).when(service).getCurrentUserId();

        assertThrows(SecurityException.class, () -> service.sendMessage(dto, Locale.ENGLISH));
    }
}
