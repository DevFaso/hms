package com.example.hms.service;

import static com.example.hms.config.SecurityConstants.ROLE_DOCTOR;
import static com.example.hms.config.SecurityConstants.ROLE_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_LAB_SCIENTIST;
import static com.example.hms.config.SecurityConstants.ROLE_MIDWIFE;
import static com.example.hms.config.SecurityConstants.ROLE_NURSE;
import static com.example.hms.config.SecurityConstants.ROLE_PATIENT;
import static com.example.hms.config.SecurityConstants.ROLE_RECEPTIONIST;
import static com.example.hms.config.SecurityConstants.ROLE_STAFF;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ChatMessageMapper;
import com.example.hms.model.ChatAttachment;
import com.example.hms.model.ChatMessage;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.ChatAttachmentDTO;
import com.example.hms.payload.dto.ChatConversationSummaryDTO;
import com.example.hms.payload.dto.ChatMessageRequestDTO;
import com.example.hms.payload.dto.ChatMessageResponseDTO;
import com.example.hms.repository.ChatAttachmentRepository;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl implements ChatMessageService {
    private static final String USER_NOT_FOUND_KEY = "user.notfound";
    private static final String TIMESTAMP_FIELD = "timestamp";

    /**
     * Hierarchical messaging rules — maps each role to the set of roles it may message.
     * <ul>
     *   <li>SUPER_ADMIN — can reach everyone (no restriction applied)</li>
     *   <li>HOSPITAL_ADMIN — can reach SUPER_ADMIN + all staff/patient roles in their hospital</li>
     *   <li>Clinical roles (DOCTOR, NURSE, MIDWIFE) — can reach each other, HOSPITAL_ADMIN, support staff, and patients</li>
     *   <li>Support roles (RECEPTIONIST, LAB_SCIENTIST, STAFF) — can reach clinical staff and HOSPITAL_ADMIN (escalation)</li>
     *   <li>PATIENT — can only reach their clinical care roles (DOCTOR, NURSE, MIDWIFE)</li>
     * </ul>
     */
    private static final Map<String, Set<String>> ALLOWED_MESSAGE_TARGETS = Map.ofEntries(
        Map.entry(ROLE_HOSPITAL_ADMIN, Set.of(
            ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN,
            ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
            ROLE_RECEPTIONIST, ROLE_LAB_SCIENTIST, ROLE_STAFF,
            ROLE_PATIENT
        )),
        Map.entry(ROLE_DOCTOR, Set.of(
            ROLE_HOSPITAL_ADMIN,
            ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
            ROLE_RECEPTIONIST, ROLE_LAB_SCIENTIST, ROLE_STAFF,
            ROLE_PATIENT
        )),
        Map.entry(ROLE_NURSE, Set.of(
            ROLE_HOSPITAL_ADMIN,
            ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
            ROLE_RECEPTIONIST, ROLE_LAB_SCIENTIST, ROLE_STAFF,
            ROLE_PATIENT
        )),
        Map.entry(ROLE_MIDWIFE, Set.of(
            ROLE_HOSPITAL_ADMIN,
            ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
            ROLE_RECEPTIONIST, ROLE_LAB_SCIENTIST, ROLE_STAFF,
            ROLE_PATIENT
        )),
        Map.entry(ROLE_RECEPTIONIST, Set.of(
            ROLE_HOSPITAL_ADMIN,
            ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
            ROLE_RECEPTIONIST, ROLE_LAB_SCIENTIST, ROLE_STAFF
        )),
        Map.entry(ROLE_LAB_SCIENTIST, Set.of(
            ROLE_HOSPITAL_ADMIN,
            ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
            ROLE_RECEPTIONIST, ROLE_LAB_SCIENTIST, ROLE_STAFF
        )),
        Map.entry(ROLE_STAFF, Set.of(
            ROLE_HOSPITAL_ADMIN,
            ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
            ROLE_RECEPTIONIST, ROLE_LAB_SCIENTIST, ROLE_STAFF
        )),
        Map.entry(ROLE_PATIENT, Set.of(
            ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE
        ))
    );


    private static final int MAX_ATTACHMENTS_PER_MESSAGE = 4;
    private static final int MIN_AUDIO_DURATION_SECONDS = 1;
    private static final int MAX_AUDIO_DURATION_SECONDS = 90;

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final ChatMessageMapper chatMessageMapper;
    private final MessageSource messageSource;
    private final UserRoleHospitalAssignmentRepository userRoleHospitalAssignmentRepository;
    private final com.example.hms.repository.HospitalRepository hospitalRepository;
    private final NotificationService notificationService;
    private final ChatAttachmentRepository chatAttachmentRepository;
    private final FileUploadService fileUploadService;

    @Override
    @Transactional
    public ChatMessageResponseDTO sendMessage(ChatMessageRequestDTO dto, Locale locale) {
        boolean hasContent = dto.getContent() != null && !dto.getContent().isBlank();
        boolean hasAttachments = dto.getAttachments() != null && !dto.getAttachments().isEmpty();
        if (!hasContent && !hasAttachments) {
            throw new IllegalArgumentException("A message must carry either content or at least one attachment");
        }

        UUID currentUserId = getCurrentUserId(); // Sender ID

        User sender = userRepository.findById(currentUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Sender not found"));

        // Resolve recipient by UUID or email
        User recipient;
        if (dto.getRecipientId() != null) {
            recipient = userRepository.findById(dto.getRecipientId())
                .orElseGet(() -> staffRepository.findById(dto.getRecipientId())
                    .map(Staff::getUser)
                    .orElseThrow(() -> new ResourceNotFoundException("Recipient not found")));
        } else if (dto.getRecipientEmail() != null && !dto.getRecipientEmail().isBlank()) {
            recipient = userRepository.findByEmail(dto.getRecipientEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Recipient not found"));
        } else {
            throw new IllegalArgumentException("Either recipientId or recipientEmail is required");
        }

        if (sender.getId().equals(recipient.getId())) {
            throw new SecurityException("Cannot send a chat message to yourself.");
        }

        // Enforce role-based messaging hierarchy. Sender roles are
        // read from SecurityContextHolder inside the validator; the
        // sender User itself is not needed (Sonar S1172 / Pattern 18
        // in docs/SonarQubeInstructions.md).
        validateMessageHierarchy(recipient);

        // Check if sender is SUPER_ADMIN (skip hospital/assignment validation)
        boolean isSuperAdmin = isSuperAdminContext();

        UserRoleHospitalAssignment assignment = null;
        if (!isSuperAdmin) {
            // Resolve hospital by name when explicitly provided
            com.example.hms.model.Hospital hospital = null;
            if (dto.getHospitalName() != null && !dto.getHospitalName().isBlank()) {
                hospital = hospitalRepository.findByNameIgnoreCase(dto.getHospitalName())
                    .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + dto.getHospitalName()));
            }

            if (hospital != null) {
                // Hospital context provided: verify sender is assigned to that hospital
                validateSenderHospitalAssignment(sender.getId(), hospital.getId());
                // Attempt to resolve a specific role assignment when roleCode is given
                if (dto.getRoleCode() != null && !dto.getRoleCode().isBlank()) {
                    assignment = getSenderAssignment(sender.getId(), hospital.getId(), dto.getRoleCode());
                } else {
                    // No role code — find any active assignment for this user in the hospital
                    assignment = userRoleHospitalAssignmentRepository
                        .findFirstByUser_IdAndHospital_IdAndActiveTrue(sender.getId(), hospital.getId())
                        .orElse(null);
                }
            } else {
                // No hospital context provided — just ensure the sender is a legitimate active user
                if (!userRoleHospitalAssignmentRepository.existsByUserIdAndActiveTrue(sender.getId())) {
                    throw new SecurityException("Sender has no active role assignment in the system.");
                }
                // assignment stays null — cross-hospital / general chat
            }
        }

        ChatMessage message = ChatMessage.builder()
            .sender(sender)
            .recipient(recipient)
            .content(dto.getContent())
            .sentAt(LocalDateTime.now())
            .assignment(assignment)
            .read(false)
            .build();

        ChatMessage saved = chatMessageRepository.save(message);

        List<ChatAttachment> persistedAttachments = persistAttachments(saved, dto.getAttachments());

        notificationService.createNotification(
            "New message from " + sender.getFirstName() + " " + sender.getLastName(),
            recipient.getUsername()
        );

        return chatMessageMapper.toChatMessageResponseDTO(saved, persistedAttachments);
    }

    /**
     * Re-resolve each inbound attachment from its server-assigned storageKey, ignoring all
     * other client-supplied descriptor fields, and persist a row pointing at it. Client-supplied
     * publicUrl/displayName/contentType/sizeBytes/sha256 are recomputed from disk by
     * {@link FileUploadService#resolveChatAttachment}; durationSeconds is preserved only for
     * AUDIO with a 1..90 s clamp and forced null for PHOTO. The unique storage_key index races
     * are caught and turned into 400s.
     * <p>
     * Bounded by {@link #MAX_ATTACHMENTS_PER_MESSAGE} so a sender can't fan out an arbitrary
     * number of media rows under one chat send.
     */
    private List<ChatAttachment> persistAttachments(ChatMessage message, List<ChatAttachmentDTO> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return List.of();
        }
        if (incoming.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new IllegalArgumentException(
                "A chat message may carry at most " + MAX_ATTACHMENTS_PER_MESSAGE + " attachments");
        }

        List<ChatAttachment> persisted = new ArrayList<>(incoming.size());
        for (ChatAttachmentDTO dto : incoming) {
            if (dto == null) {
                throw new IllegalArgumentException("Attachment payload is required");
            }
            if (dto.getKind() == null) {
                throw new IllegalArgumentException("Attachment kind is required");
            }
            FileUploadService.StoredFileDescriptor descriptor;
            try {
                descriptor = fileUploadService.resolveChatAttachment(dto.getStorageKey(), dto.getKind());
            } catch (java.io.IOException ex) {
                throw new IllegalArgumentException("Failed to resolve chat attachment", ex);
            }
            Integer duration = clampAudioDuration(dto.getKind(), dto.getDurationSeconds());
            if (chatAttachmentRepository.existsByStorageKey(descriptor.storageKey())) {
                throw new IllegalArgumentException(
                    "Attachment storageKey already linked to another message");
            }
            ChatAttachment attachment = ChatAttachment.builder()
                .message(message)
                .kind(dto.getKind())
                .storageKey(descriptor.storageKey())
                .publicUrl(descriptor.publicUrl())
                .displayName(descriptor.displayName())
                .contentType(descriptor.contentType())
                .sizeBytes(descriptor.sizeBytes())
                .sha256(descriptor.sha256())
                .durationSeconds(duration)
                .build();
            try {
                persisted.add(chatAttachmentRepository.saveAndFlush(attachment));
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                throw new IllegalArgumentException(
                    "Attachment storageKey already linked to another message", ex);
            }
        }
        return persisted;
    }

    private Integer clampAudioDuration(com.example.hms.enums.ChatAttachmentKind kind, Integer durationSeconds) {
        if (kind == com.example.hms.enums.ChatAttachmentKind.PHOTO) {
            return null;
        }
        if (durationSeconds == null) {
            throw new IllegalArgumentException("Voice memo duration is required");
        }
        if (durationSeconds < MIN_AUDIO_DURATION_SECONDS || durationSeconds > MAX_AUDIO_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                "Voice memo duration must be between " + MIN_AUDIO_DURATION_SECONDS
                    + " and " + MAX_AUDIO_DURATION_SECONDS + " seconds");
        }
        return durationSeconds;
    }

    /** Batch-load attachments for a page of messages so the response avoids N+1. */
    private Map<UUID, List<ChatAttachment>> loadAttachmentsByMessage(Collection<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = messages.stream()
            .map(ChatMessage::getId)
            .filter(java.util.Objects::nonNull)
            .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ChatAttachment>> grouped = new HashMap<>();
        chatAttachmentRepository.findByMessage_IdInOrderByMessage_IdAscCreatedAtAsc(ids)
            .forEach(att -> grouped
                .computeIfAbsent(att.getMessage().getId(), k -> new ArrayList<>())
                .add(att));
        return grouped;
    }

    private boolean isSuperAdminContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
            .anyMatch(a -> ROLE_SUPER_ADMIN.equals(a.getAuthority()));
    }

    /**
     * Validates that the sender's role is allowed to message the recipient based on
     * the hospital role hierarchy. SUPER_ADMIN can message anyone. All other roles
     * must have at least one role that permits messaging at least one of the
     * recipient's roles.
     *
     * <p>The sender's roles are read from {@link SecurityContextHolder} (the
     * authenticated principal), not from a method argument — that's the source of
     * truth and avoids any drift between the User entity's persisted roles and the
     * caller's effective authorities. Sonar S1172 / Pattern 18 in
     * docs/SonarQubeInstructions.md.</p>
     */
    private void validateMessageHierarchy(User recipient) {
        // SUPER_ADMIN bypasses all hierarchy checks
        if (isSuperAdminContext()) {
            return;
        }

        // Collect sender role names from SecurityContext authorities
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> senderRoles = auth.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .collect(java.util.stream.Collectors.toSet());

        // Collect recipient role names from their user-role associations
        Set<String> recipientRoles = recipient.getUserRoles().stream()
            .filter(ur -> ur.getRole() != null)
            .map(ur -> {
                String roleName = ur.getRole().getName();
                return roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName;
            })
            .collect(java.util.stream.Collectors.toSet());

        // Check if any sender role is allowed to message any recipient role
        boolean allowed = senderRoles.stream().anyMatch(senderRole -> {
            Set<String> targets = ALLOWED_MESSAGE_TARGETS.get(senderRole);
            if (targets == null) {
                return false; // Unknown role — deny by default
            }
            return recipientRoles.stream().anyMatch(targets::contains);
        });

        if (!allowed) {
            throw new SecurityException("Your role does not permit messaging this user.");
        }
    }

    private void validateSenderHospitalAssignment(UUID senderId, UUID hospitalId) {
        boolean exists = userRoleHospitalAssignmentRepository
            .existsByUserIdAndHospitalIdAndActiveTrue(senderId, hospitalId);
        if (!exists) {
            throw new SecurityException("Sender is not assigned to the specified hospital.");
        }
    }

    private UserRoleHospitalAssignment getSenderAssignment(UUID userId, UUID hospitalId, String roleCode) {
        return userRoleHospitalAssignmentRepository
            .findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, hospitalId, roleCode)
            .orElseThrow(() -> new ResourceNotFoundException("No active assignment with role " + roleCode + " found for user in hospital"));
    }

    protected UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new SecurityException("Unauthorized: No authenticated user found");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails userDetails) {
            return userDetails.getUserId();
        }

        throw new SecurityException("Invalid principal type in security context");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageResponseDTO> getChatHistory(UUID user1Id, UUID user2Id, int page, int size, Locale locale) {
        User user1 = userRepository.findById(user1Id)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(USER_NOT_FOUND_KEY, new Object[]{user1Id}, locale)));
        User user2 = userRepository.findById(user2Id)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(USER_NOT_FOUND_KEY, new Object[]{user2Id}, locale)));

        Pageable pageable = PageRequest.of(page, size, Sort.by(TIMESTAMP_FIELD).descending());
        Page<ChatMessage> messages = chatMessageRepository.findChatBetweenUsers(user1, user2, pageable);

        Map<UUID, List<ChatAttachment>> attachmentsByMessage = loadAttachmentsByMessage(messages.getContent());
        return messages.stream()
            .map(m -> chatMessageMapper.toChatMessageResponseDTO(m, attachmentsByMessage))
            .toList();
    }

    @Override
    @Transactional
    public void markMessagesAsRead(UUID senderId, UUID recipientId, Locale locale) {
        User sender = userRepository.findById(senderId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(USER_NOT_FOUND_KEY, new Object[]{senderId}, locale)));
        User recipient = userRepository.findById(recipientId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(USER_NOT_FOUND_KEY, new Object[]{recipientId}, locale)));

        List<ChatMessage> messages = chatMessageRepository.findUnreadMessages(sender, recipient);
        messages.forEach(m -> m.setRead(true));
        chatMessageRepository.saveAll(messages);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageResponseDTO> searchMessages(UUID user1Id, UUID user2Id, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(TIMESTAMP_FIELD).descending());
        Page<ChatMessage> messages = chatMessageRepository
            .searchMessagesBetweenUsers(user1Id, user2Id, keyword, pageable);

        Map<UUID, List<ChatAttachment>> attachmentsByMessage = loadAttachmentsByMessage(messages.getContent());
        return messages.stream()
            .map(m -> chatMessageMapper.toChatMessageResponseDTO(m, attachmentsByMessage))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageResponseDTO> getAllMessagesForUser(UUID userId, Boolean unread, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(TIMESTAMP_FIELD).descending());
        Page<ChatMessage> messages;
        if (Boolean.TRUE.equals(unread)) {
            messages = chatMessageRepository.findAllUnreadMessagesForUser(userId, pageable);
        } else {
            messages = chatMessageRepository.findAllMessagesForUser(userId, pageable);
        }

        Map<UUID, List<ChatAttachment>> attachmentsByMessage = loadAttachmentsByMessage(messages.getContent());
        return messages.stream()
            .map(m -> chatMessageMapper.toChatMessageResponseDTO(m, attachmentsByMessage))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatConversationSummaryDTO> getUserConversations(UUID userId, int page, int size) {
        int offset = page * size;

        List<Object[]> rawResults = chatMessageRepository.findLatestConversations(userId, size, offset);

        return rawResults.stream().map(row -> {
            // UUID messageId = (UUID) row[0]; // unused
            UUID senderId = (UUID) row[1];
            UUID recipientId = (UUID) row[2];
            String content = (String) row[3];
            LocalDateTime timestamp = row[4] instanceof java.sql.Timestamp ts
                    ? ts.toLocalDateTime()
                    : (LocalDateTime) row[4];
            boolean read = Boolean.TRUE.equals(row[5]);

            UUID otherUserId = senderId.equals(userId) ? recipientId : senderId;

            User otherUser = userRepository.findById(otherUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + otherUserId));

            User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

            int unreadCount = chatMessageRepository.countBySenderAndRecipientAndReadFalse(otherUser, currentUser);

            return ChatConversationSummaryDTO.builder()
                .conversationUserId(otherUser.getId())
                .conversationUserName(otherUser.getFirstName() + " " + otherUser.getLastName())
                .lastMessageContent(content)
                .lastMessageTimestamp(timestamp)
                .lastMessageRead(read)
                .unreadCount(unreadCount)
                .build();
        }).toList();
    }

    @Override
    public List<ChatMessageResponseDTO> getMessagesBySenderEmail(String email) {
        List<ChatMessage> messages = chatMessageRepository.findBySenderEmail(email);
        return mapWithAttachments(messages);
    }

    @Override
    public List<ChatMessageResponseDTO> getMessagesByRecipientEmail(String email) {
        List<ChatMessage> messages = chatMessageRepository.findByRecipientEmail(email);
        return mapWithAttachments(messages);
    }

    @Override
    public List<ChatMessageResponseDTO> getMessagesBySenderUsername(String username) {
        List<ChatMessage> messages = chatMessageRepository.findBySenderUsername(username);
        return mapWithAttachments(messages);
    }

    @Override
    public List<ChatMessageResponseDTO> getMessagesByRecipientUsername(String username) {
        List<ChatMessage> messages = chatMessageRepository.findByRecipientUsername(username);
        return mapWithAttachments(messages);
    }

    private List<ChatMessageResponseDTO> mapWithAttachments(List<ChatMessage> messages) {
        Map<UUID, List<ChatAttachment>> attachmentsByMessage = loadAttachmentsByMessage(messages);
        return messages.stream()
            .map(m -> chatMessageMapper.toChatMessageResponseDTO(m, attachmentsByMessage))
            .toList();
    }
}
