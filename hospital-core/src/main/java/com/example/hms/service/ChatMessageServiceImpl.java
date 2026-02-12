package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ChatMessageMapper;
import com.example.hms.model.ChatMessage;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.ChatConversationSummaryDTO;
import com.example.hms.payload.dto.ChatMessageRequestDTO;
import com.example.hms.payload.dto.ChatMessageResponseDTO;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl implements ChatMessageService {

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final ChatMessageMapper chatMessageMapper;
    private final MessageSource messageSource;
    private final UserRoleHospitalAssignmentRepository userRoleHospitalAssignmentRepository;
    private final com.example.hms.repository.HospitalRepository hospitalRepository;

    @Override
    @Transactional
    public ChatMessageResponseDTO sendMessage(ChatMessageRequestDTO dto, Locale locale) {
        UUID currentUserId = getCurrentUserId(); // Sender ID

        User sender = userRepository.findById(currentUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Sender not found"));

        if (sender.getEmail().equalsIgnoreCase(dto.getRecipientEmail())) {
            throw new SecurityException("Cannot send a chat message to yourself.");
        }

        User recipient = userRepository.findByEmail(dto.getRecipientEmail())
            .orElseThrow(() -> new ResourceNotFoundException("Recipient not found"));

        // Resolve hospital by name using HospitalRepository
        com.example.hms.model.Hospital hospital = null;
        if (dto.getHospitalName() != null && !dto.getHospitalName().isBlank()) {
            hospital = hospitalRepository.findByNameIgnoreCase(dto.getHospitalName())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + dto.getHospitalName()));
        }

        validateSenderAssignment(sender.getId(), hospital != null ? hospital.getId() : null);

        UserRoleHospitalAssignment assignment = getSenderAssignment(
            sender.getId(),
            hospital != null ? hospital.getId() : null,
            dto.getRoleCode()
        );

        ChatMessage message = ChatMessage.builder()
            .sender(sender)
            .recipient(recipient)
            .content(dto.getContent())
            .sentAt(LocalDateTime.now())
            .assignment(assignment)
            .read(false)
            .build();

        ChatMessage saved = chatMessageRepository.save(message);
        return chatMessageMapper.toChatMessageResponseDTO(saved);
    }

    private void validateSenderAssignment(UUID senderId, UUID hospitalId) {
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
                messageSource.getMessage("user.notfound", new Object[]{user1Id}, locale)));
        User user2 = userRepository.findById(user2Id)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage("user.notfound", new Object[]{user2Id}, locale)));

        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        Page<ChatMessage> messages = chatMessageRepository.findChatBetweenUsers(user1, user2, pageable);

        return messages.stream()
            .map(chatMessageMapper::toChatMessageResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void markMessagesAsRead(UUID senderId, UUID recipientId, Locale locale) {
        User sender = userRepository.findById(senderId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage("user.notfound", new Object[]{senderId}, locale)));
        User recipient = userRepository.findById(recipientId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage("user.notfound", new Object[]{recipientId}, locale)));

        List<ChatMessage> messages = chatMessageRepository.findUnreadMessages(sender, recipient);
        messages.forEach(m -> m.setRead(true));
        chatMessageRepository.saveAll(messages);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageResponseDTO> searchMessages(UUID user1Id, UUID user2Id, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        Page<ChatMessage> messages = chatMessageRepository
            .searchMessagesBetweenUsers(user1Id, user2Id, keyword, pageable);

        return messages.stream()
            .map(chatMessageMapper::toChatMessageResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageResponseDTO> getAllMessagesForUser(UUID userId, Boolean unread, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        Page<ChatMessage> messages;
        if (Boolean.TRUE.equals(unread)) {
            messages = chatMessageRepository.findAllUnreadMessagesForUser(userId, pageable);
        } else {
            messages = chatMessageRepository.findAllMessagesForUser(userId, pageable);
        }

        return messages.stream()
            .map(chatMessageMapper::toChatMessageResponseDTO)
            .collect(Collectors.toList());
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
            LocalDateTime timestamp = ((java.sql.Timestamp) row[4]).toLocalDateTime();
            boolean read = (boolean) row[5];

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
        }).collect(Collectors.toList());
    }

    @Override
public List<ChatMessageResponseDTO> getMessagesBySenderEmail(String email) {
    List<ChatMessage> messages = chatMessageRepository.findBySenderEmail(email);
    return messages.stream().map(chatMessageMapper::toChatMessageResponseDTO).collect(Collectors.toList());
}

@Override
public List<ChatMessageResponseDTO> getMessagesByRecipientEmail(String email) {
    List<ChatMessage> messages = chatMessageRepository.findByRecipientEmail(email);
    return messages.stream().map(chatMessageMapper::toChatMessageResponseDTO).collect(Collectors.toList());
}

@Override
public List<ChatMessageResponseDTO> getMessagesBySenderUsername(String username) {
    List<ChatMessage> messages = chatMessageRepository.findBySenderUsername(username);
    return messages.stream().map(chatMessageMapper::toChatMessageResponseDTO).collect(Collectors.toList());
}

@Override
public List<ChatMessageResponseDTO> getMessagesByRecipientUsername(String username) {
    List<ChatMessage> messages = chatMessageRepository.findByRecipientUsername(username);
    return messages.stream().map(chatMessageMapper::toChatMessageResponseDTO).collect(Collectors.toList());
}
}
