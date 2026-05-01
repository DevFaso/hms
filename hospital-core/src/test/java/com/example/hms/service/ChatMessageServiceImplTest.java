package com.example.hms.service;

import com.example.hms.enums.ChatAttachmentKind;
import com.example.hms.mapper.ChatMessageMapper;
import com.example.hms.model.ChatAttachment;
import com.example.hms.model.ChatMessage;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleId;
import com.example.hms.payload.dto.ChatAttachmentDTO;
import com.example.hms.payload.dto.ChatMessageRequestDTO;
import com.example.hms.payload.dto.ChatMessageResponseDTO;
import com.example.hms.repository.ChatAttachmentRepository;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageServiceImplTest {
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private UserRepository userRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRoleHospitalAssignmentRepository userRoleHospitalAssignmentRepository;
    @Mock private ChatMessageMapper chatMessageMapper;
    @Mock private MessageSource messageSource;
    @Mock private NotificationService notificationService;
    @Mock private ChatAttachmentRepository chatAttachmentRepository;
    @Mock private FileUploadService fileUploadService;

    @InjectMocks
    private ChatMessageServiceImpl chatMessageService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(String role) {
        var auth = new UsernamePasswordAuthenticationToken(
            "user", "pass",
            List.of(new SimpleGrantedAuthority(role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private User userWithRole(UUID id, String roleName) {
        User user = new User();
        user.setId(id);
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setName(roleName);
        role.setCode(roleName);
        UserRole ur = UserRole.builder()
            .id(new UserRoleId(id, role.getId()))
            .user(user)
            .role(role)
            .build();
        user.getUserRoles().add(ur);
        return user;
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
        UUID recipientId = UUID.randomUUID();
        User sender = new User();
        sender.setId(senderId);
        sender.setEmail("sender@example.com");
        User recipient = userWithRole(recipientId, "ROLE_DOCTOR");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipient));
        when(hospitalRepository.findByNameIgnoreCase("General Hospital")).thenReturn(Optional.empty());
        setSecurityContext("ROLE_NURSE");
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
        User recipient = userWithRole(recipientId, "ROLE_DOCTOR");

        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
        when(userRoleHospitalAssignmentRepository.existsByUserIdAndActiveTrue(senderId)).thenReturn(true);

        setSecurityContext("ROLE_NURSE");

        ChatMessage savedMessage = new ChatMessage();
        savedMessage.setSender(sender);
        savedMessage.setRecipient(recipient);
        savedMessage.setContent("testing within hospital");
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(savedMessage);
        when(chatMessageMapper.toChatMessageResponseDTO(any(ChatMessage.class), any(java.util.Collection.class)))
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
        User recipient = userWithRole(recipientId, "ROLE_DOCTOR");

        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
        when(userRoleHospitalAssignmentRepository.existsByUserIdAndActiveTrue(senderId)).thenReturn(false);

        setSecurityContext("ROLE_NURSE");

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("testing within hospital")
            .build();

        ChatMessageServiceImpl service = spy(chatMessageService);
        doReturn(senderId).when(service).getCurrentUserId();

        assertThrows(SecurityException.class, () -> service.sendMessage(dto, Locale.ENGLISH));
    }

    // ---- P1 #10 telehealth attachment paths ----

    private static FileUploadService.StoredFileDescriptor descriptorFor(String storageKey, String contentType) {
        String filename = storageKey.substring(storageKey.lastIndexOf('/') + 1);
        return new FileUploadService.StoredFileDescriptor(
            storageKey,
            "https://hms.test" + storageKey,
            filename,
            contentType,
            1024L,
            "deadbeef".repeat(8) // 64-char sha
        );
    }

    private ChatMessageServiceImpl primeAttachmentSendScenario(UUID senderId, UUID recipientId,
                                                               java.util.List<ChatAttachmentDTO> attachments,
                                                               ChatMessageRequestDTO dto) {
        User sender = new User();
        sender.setId(senderId);
        User recipient = userWithRole(recipientId, "ROLE_DOCTOR");

        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
        when(userRoleHospitalAssignmentRepository.existsByUserIdAndActiveTrue(senderId)).thenReturn(true);

        setSecurityContext("ROLE_NURSE");

        ChatMessage savedMessage = new ChatMessage();
        savedMessage.setSender(sender);
        savedMessage.setRecipient(recipient);
        savedMessage.setContent(dto.getContent());
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(savedMessage);
        when(chatAttachmentRepository.saveAndFlush(any(ChatAttachment.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(chatMessageMapper.toChatMessageResponseDTO(any(ChatMessage.class), any(java.util.Collection.class)))
            .thenReturn(ChatMessageResponseDTO.builder().build());

        dto.setAttachments(attachments);

        ChatMessageServiceImpl service = spy(chatMessageService);
        doReturn(senderId).when(service).getCurrentUserId();
        return service;
    }

    @Test
    void sendMessage_persistsPhotoAttachment_whenStorageKeyIsNew() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        ChatAttachmentDTO photo = ChatAttachmentDTO.builder()
            .storageKey("/uploads/chat-attachments/abc.jpg")
            .kind(ChatAttachmentKind.PHOTO)
            // Client-supplied descriptors below MUST be ignored by the service:
            .publicUrl("https://malicious/abc.jpg")
            .displayName("not-trusted.jpg")
            .contentType("application/octet-stream")
            .sizeBytes(999_999_999L)
            .sha256("tampered")
            .build();

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("see attached")
            .build();

        ChatMessageServiceImpl service = primeAttachmentSendScenario(
            senderId, recipientId, java.util.List.of(photo), dto);

        when(fileUploadService.resolveChatAttachment(photo.getStorageKey(), ChatAttachmentKind.PHOTO))
            .thenReturn(descriptorFor(photo.getStorageKey(), "image/jpeg"));
        when(chatAttachmentRepository.existsByStorageKey(photo.getStorageKey())).thenReturn(false);

        ChatMessageResponseDTO result = service.sendMessage(dto, Locale.ENGLISH);
        assertNotNull(result);
        org.mockito.ArgumentCaptor<ChatAttachment> captor =
            org.mockito.ArgumentCaptor.forClass(ChatAttachment.class);
        verify(chatAttachmentRepository, times(1)).saveAndFlush(captor.capture());
        ChatAttachment saved = captor.getValue();
        // Server-resolved fields, not the malicious client values:
        assertEquals("image/jpeg", saved.getContentType());
        assertEquals(1024L, saved.getSizeBytes());
        assertEquals("abc.jpg", saved.getDisplayName());
        assertTrue(saved.getPublicUrl().startsWith("https://hms.test"));
        // PHOTO must drop any client-supplied duration:
        assertEquals(null, saved.getDurationSeconds());
    }

    @Test
    void sendMessage_persistsAudioWithClampedDuration() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        ChatAttachmentDTO audio = ChatAttachmentDTO.builder()
            .storageKey("/uploads/chat-attachments/v.ogg")
            .kind(ChatAttachmentKind.AUDIO)
            .durationSeconds(45)
            .build();

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content(null)
            .build();

        ChatMessageServiceImpl service = primeAttachmentSendScenario(
            senderId, recipientId, java.util.List.of(audio), dto);

        when(fileUploadService.resolveChatAttachment(audio.getStorageKey(), ChatAttachmentKind.AUDIO))
            .thenReturn(descriptorFor(audio.getStorageKey(), "audio/ogg"));
        when(chatAttachmentRepository.existsByStorageKey(audio.getStorageKey())).thenReturn(false);

        service.sendMessage(dto, Locale.ENGLISH);

        org.mockito.ArgumentCaptor<ChatAttachment> captor =
            org.mockito.ArgumentCaptor.forClass(ChatAttachment.class);
        verify(chatAttachmentRepository).saveAndFlush(captor.capture());
        assertEquals(45, captor.getValue().getDurationSeconds());
    }

    @Test
    void sendMessage_rejectsAudioWithDurationOutOfRange() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        ChatAttachmentDTO audio = ChatAttachmentDTO.builder()
            .storageKey("/uploads/chat-attachments/v.ogg")
            .kind(ChatAttachmentKind.AUDIO)
            .durationSeconds(120)
            .build();

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("voice memo")
            .build();

        ChatMessageServiceImpl service = primeAttachmentSendScenario(
            senderId, recipientId, java.util.List.of(audio), dto);

        when(fileUploadService.resolveChatAttachment(audio.getStorageKey(), ChatAttachmentKind.AUDIO))
            .thenReturn(descriptorFor(audio.getStorageKey(), "audio/ogg"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.sendMessage(dto, Locale.ENGLISH));
        assertTrue(ex.getMessage().contains("between 1 and 90"));
        verify(chatAttachmentRepository, never()).saveAndFlush(any(ChatAttachment.class));
    }

    @Test
    void sendMessage_rejectsAttachmentWithReusedStorageKey() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        ChatAttachmentDTO photo = ChatAttachmentDTO.builder()
            .storageKey("/uploads/chat-attachments/dup.jpg")
            .kind(ChatAttachmentKind.PHOTO)
            .build();

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("dup link")
            .build();

        ChatMessageServiceImpl service = primeAttachmentSendScenario(
            senderId, recipientId, java.util.List.of(photo), dto);

        when(fileUploadService.resolveChatAttachment(photo.getStorageKey(), ChatAttachmentKind.PHOTO))
            .thenReturn(descriptorFor(photo.getStorageKey(), "image/jpeg"));
        when(chatAttachmentRepository.existsByStorageKey(photo.getStorageKey())).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.sendMessage(dto, Locale.ENGLISH));
        assertTrue(ex.getMessage().contains("already linked"));
        verify(chatAttachmentRepository, never()).saveAndFlush(any(ChatAttachment.class));
    }

    @Test
    void sendMessage_rejectsOnDataIntegrityViolationRace() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        ChatAttachmentDTO photo = ChatAttachmentDTO.builder()
            .storageKey("/uploads/chat-attachments/race.jpg")
            .kind(ChatAttachmentKind.PHOTO)
            .build();

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("race")
            .build();

        ChatMessageServiceImpl service = primeAttachmentSendScenario(
            senderId, recipientId, java.util.List.of(photo), dto);

        when(fileUploadService.resolveChatAttachment(photo.getStorageKey(), ChatAttachmentKind.PHOTO))
            .thenReturn(descriptorFor(photo.getStorageKey(), "image/jpeg"));
        when(chatAttachmentRepository.existsByStorageKey(photo.getStorageKey())).thenReturn(false);
        when(chatAttachmentRepository.saveAndFlush(any(ChatAttachment.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("race"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.sendMessage(dto, Locale.ENGLISH));
        assertTrue(ex.getMessage().contains("already linked"));
    }

    @Test
    void sendMessage_rejectsMoreThanFourAttachments() {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        java.util.List<ChatAttachmentDTO> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tooMany.add(ChatAttachmentDTO.builder()
                .storageKey("/uploads/chat-attachments/" + i + ".jpg")
                .kind(ChatAttachmentKind.PHOTO)
                .build());
        }

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("flood")
            .build();

        ChatMessageServiceImpl service = primeAttachmentSendScenario(
            senderId, recipientId, tooMany, dto);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.sendMessage(dto, Locale.ENGLISH));
        assertTrue(ex.getMessage().contains("at most 4"));
        verify(chatAttachmentRepository, never()).saveAndFlush(any(ChatAttachment.class));
    }

    @Test
    void sendMessage_skipsAttachmentRepoWhenNoAttachments() {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("plain text")
            .build();

        ChatMessageServiceImpl service = primeAttachmentSendScenario(
            senderId, recipientId, null, dto);

        ChatMessageResponseDTO result = service.sendMessage(dto, Locale.ENGLISH);
        assertNotNull(result);
        verify(chatAttachmentRepository, never()).saveAndFlush(any(ChatAttachment.class));
        verify(chatAttachmentRepository, never()).existsByStorageKey(any(String.class));
    }

    @Test
    void sendMessage_rejectsAttachmentWithoutKind() {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        ChatAttachmentDTO bad = ChatAttachmentDTO.builder()
            .storageKey("/uploads/chat-attachments/x.jpg")
            .kind(null)
            .build();

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("kindless")
            .build();

        ChatMessageServiceImpl service = primeAttachmentSendScenario(
            senderId, recipientId, java.util.List.of(bad), dto);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.sendMessage(dto, Locale.ENGLISH));
        assertEquals("Attachment kind is required", ex.getMessage());
        verify(chatAttachmentRepository, never()).saveAndFlush(any(ChatAttachment.class));
    }

    @Test
    void sendMessage_rejectsEmptyContentAndNoAttachments() {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        User sender = new User();
        sender.setId(senderId);
        User recipient = userWithRole(recipientId, "ROLE_DOCTOR");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
        setSecurityContext("ROLE_NURSE");

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content("   ")
            .build();

        ChatMessageServiceImpl service = spy(chatMessageService);
        doReturn(senderId).when(service).getCurrentUserId();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.sendMessage(dto, Locale.ENGLISH));
        assertTrue(ex.getMessage().contains("content or at least one attachment"));
    }

    @Test
    void sendMessage_allowsAttachmentOnlySend() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        ChatAttachmentDTO photo = ChatAttachmentDTO.builder()
            .storageKey("/uploads/chat-attachments/onlymedia.jpg")
            .kind(ChatAttachmentKind.PHOTO)
            .build();

        ChatMessageRequestDTO dto = ChatMessageRequestDTO.builder()
            .recipientId(recipientId)
            .content(null)
            .build();

        ChatMessageServiceImpl service = primeAttachmentSendScenario(
            senderId, recipientId, java.util.List.of(photo), dto);

        when(fileUploadService.resolveChatAttachment(photo.getStorageKey(), ChatAttachmentKind.PHOTO))
            .thenReturn(descriptorFor(photo.getStorageKey(), "image/jpeg"));
        when(chatAttachmentRepository.existsByStorageKey(photo.getStorageKey())).thenReturn(false);

        ChatMessageResponseDTO result = service.sendMessage(dto, Locale.ENGLISH);
        assertNotNull(result);
        verify(chatAttachmentRepository, times(1)).saveAndFlush(any(ChatAttachment.class));
    }
}
