package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.User;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.model.UserRecoveryContact;
import com.example.hms.payload.dto.credential.UserCredentialHealthDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentRequestDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactRequestDTO;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRecoveryContactRepository;
import com.example.hms.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserCredentialLifecycleServiceImpl implements UserCredentialLifecycleService {

    private final UserRepository userRepository;
    private final UserMfaEnrollmentRepository mfaEnrollmentRepository;
    private final UserRecoveryContactRepository recoveryContactRepository;

    @Override
    @Transactional
    public void recordSuccessfulLogin(UUID userId) {
        if (userId == null) {
            return;
        }
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);
        });
    }

    @Override
    @Transactional
    public List<UserCredentialHealthDTO> listCredentialHealth() {
        List<User> users = userRepository.findByIsDeletedFalse();
        if (users.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<UserMfaEnrollment>> enrollmentMap =
            mfaEnrollmentRepository.findAll().stream()
                .collect(Collectors.groupingBy(e -> e.getUser().getId()));

        Map<UUID, List<UserRecoveryContact>> contactMap =
            recoveryContactRepository.findAll().stream()
                .collect(Collectors.groupingBy(c -> c.getUser().getId()));

        return users.stream()
            .map(user -> toHealthDto(user,
                enrollmentMap.getOrDefault(user.getId(), List.of()),
                contactMap.getOrDefault(user.getId(), List.of())))
            .sorted(Comparator
                .comparing(UserCredentialHealthDTO::isForcePasswordChange).reversed()
                .thenComparing(UserCredentialHealthDTO::getLastLoginAt,
                    Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(UserCredentialHealthDTO::getUsername, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    @Override
    @Transactional
    public UserCredentialHealthDTO getCredentialHealth(UUID userId) {
        User user = userRepository.findByIdWithRolesAndProfiles(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));
        List<UserMfaEnrollment> enrollments = mfaEnrollmentRepository.findByUserId(userId);
        List<UserRecoveryContact> contacts = recoveryContactRepository.findByUserId(userId);
        return toHealthDto(user, enrollments, contacts);
    }

    @Override
    @Transactional
    public List<UserMfaEnrollmentDTO> upsertMfaEnrollments(UUID userId, List<UserMfaEnrollmentRequestDTO> payload) {
        User user = resolveUser(userId);

        List<UserMfaEnrollment> existing = mfaEnrollmentRepository.findByUserId(userId);
        Map<String, UserMfaEnrollment> existingByKey = existing.stream()
            .collect(Collectors.toMap(this::mfaKey, enrollment -> enrollment));

        Set<String> requestedKeys = payload == null ? Set.of() : payload.stream()
            .filter(Objects::nonNull)
            .map(this::mfaKey)
            .collect(Collectors.toSet());

        List<UserMfaEnrollment> toPersist = new ArrayList<>();
        if (payload != null) {
            for (UserMfaEnrollmentRequestDTO dto : payload) {
                if (dto == null || dto.getMethod() == null) {
                    continue;
                }
                String key = mfaKey(dto);
                UserMfaEnrollment enrollment = existingByKey.getOrDefault(key, UserMfaEnrollment.builder().user(user).build());
                enrollment.setUser(user);
                enrollment.setMethod(dto.getMethod());
                enrollment.setChannel(normalize(dto.getChannel()));
                enrollment.setEnabled(dto.isEnabled());
                enrollment.setPrimaryFactor(dto.isPrimaryFactor());
                enrollment.setEnrolledAt(dto.getEnrolledAt());
                enrollment.setLastVerifiedAt(dto.getLastVerifiedAt());
                toPersist.add(enrollment);
            }
        }

        // ensure only one primary factor remains
        reconcilePrimaryFlag(toPersist, UserMfaEnrollment::isPrimaryFactor, UserMfaEnrollment::setPrimaryFactor);

        List<UserMfaEnrollment> saved = mfaEnrollmentRepository.saveAll(toPersist);

        // remove obsolete
        for (UserMfaEnrollment enrollment : existing) {
            if (!requestedKeys.contains(mfaKey(enrollment))) {
                mfaEnrollmentRepository.delete(enrollment);
            }
        }

        return saved.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public List<UserRecoveryContactDTO> upsertRecoveryContacts(UUID userId, List<UserRecoveryContactRequestDTO> payload) {
        User user = resolveUser(userId);

        List<UserRecoveryContact> existing = recoveryContactRepository.findByUserId(userId);
        Map<String, UserRecoveryContact> existingByKey = existing.stream()
            .collect(Collectors.toMap(
                this::recoveryKey,
                contact -> contact,
                (left, right) -> left,
                LinkedHashMap::new
            ));

        List<UserRecoveryContactRequestDTO> sanitizedPayload = payload == null ? List.of() : payload.stream()
            .filter(this::isValidContactRequest)
            .toList();

        Set<String> requestedKeys = sanitizedPayload.stream()
            .map(this::recoveryKey)
            .collect(Collectors.toSet());

        List<UserRecoveryContact> toPersist = new ArrayList<>(sanitizedPayload.size());
        for (UserRecoveryContactRequestDTO dto : sanitizedPayload) {
            String key = recoveryKey(dto);
            UserRecoveryContact contact = existingByKey.getOrDefault(key, UserRecoveryContact.builder().user(user).build());
            contact.setUser(user);
            contact.setContactType(dto.getContactType());
            contact.setContactValue(dto.getContactValue().trim());
            contact.setVerified(dto.isVerified());
            contact.setPrimaryContact(dto.isPrimaryContact());
            contact.setNotes(normalize(dto.getNotes()));
            if (dto.isVerified()) {
                contact.setVerifiedAt(contact.getVerifiedAt() != null ? contact.getVerifiedAt() : LocalDateTime.now());
            } else {
                contact.setVerifiedAt(null);
            }
            existingByKey.put(key, contact);
            toPersist.add(contact);
        }

        reconcilePrimaryFlag(toPersist, UserRecoveryContact::isPrimaryContact, UserRecoveryContact::setPrimaryContact);

        List<UserRecoveryContact> saved = recoveryContactRepository.saveAll(toPersist);

        for (UserRecoveryContact contact : existing) {
            if (!requestedKeys.contains(recoveryKey(contact))) {
                recoveryContactRepository.delete(contact);
            }
        }

        return saved.stream().map(this::toDto).toList();
    }

    private User resolveUser(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));
    }

    private UserCredentialHealthDTO toHealthDto(User user,
                                                List<UserMfaEnrollment> enrollments,
                                                List<UserRecoveryContact> contacts) {
        long enrolledCount = enrollments.stream().filter(UserMfaEnrollment::isEnabled).count();
        long verifiedMfa = enrollments.stream()
            .filter(enrollment -> enrollment.getLastVerifiedAt() != null)
            .count();
        boolean hasPrimaryMfa = enrollments.stream().anyMatch(UserMfaEnrollment::isPrimaryFactor);

        long contactCount = contacts.size();
        long verifiedContacts = contacts.stream().filter(UserRecoveryContact::isVerified).count();
        boolean hasPrimaryContact = contacts.stream().anyMatch(UserRecoveryContact::isPrimaryContact);

        return UserCredentialHealthDTO.builder()
            .userId(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .active(user.isActive())
            .forcePasswordChange(user.isForcePasswordChange())
            .lastLoginAt(user.getLastLoginAt())
            .mfaEnrolledCount(enrolledCount)
            .verifiedMfaCount(verifiedMfa)
            .hasPrimaryMfa(hasPrimaryMfa)
            .recoveryContactCount(contactCount)
            .verifiedRecoveryContacts(verifiedContacts)
            .hasPrimaryRecoveryContact(hasPrimaryContact)
            .mfaEnrollments(enrollments.stream().map(this::toDto).toList())
            .recoveryContacts(contacts.stream().map(this::toDto).toList())
            .build();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String mfaKey(UserMfaEnrollment enrollment) {
        return key(enrollment.getMethod(), enrollment.getChannel());
    }

    private String mfaKey(UserMfaEnrollmentRequestDTO dto) {
        return key(dto.getMethod(), dto.getChannel());
    }

    private String recoveryKey(UserRecoveryContact contact) {
        return key(contact.getContactType(), contact.getContactValue());
    }

    private String recoveryKey(UserRecoveryContactRequestDTO dto) {
        return key(dto.getContactType(), dto.getContactValue());
    }

    private String key(Enum<?> type, String value) {
        String normalizedValue = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        String typeCode = type == null ? "unknown" : type.name();
        return typeCode + "::" + normalizedValue;
    }

    private UserMfaEnrollmentDTO toDto(UserMfaEnrollment enrollment) {
        return UserMfaEnrollmentDTO.builder()
            .method(enrollment.getMethod())
            .channel(enrollment.getChannel())
            .enabled(enrollment.isEnabled())
            .primaryFactor(enrollment.isPrimaryFactor())
            .enrolledAt(enrollment.getEnrolledAt())
            .lastVerifiedAt(enrollment.getLastVerifiedAt())
            .build();
    }

    private UserRecoveryContactDTO toDto(UserRecoveryContact contact) {
        return UserRecoveryContactDTO.builder()
            .id(contact.getId())
            .contactType(contact.getContactType())
            .contactValue(contact.getContactValue())
            .verified(contact.isVerified())
            .verifiedAt(contact.getVerifiedAt())
            .primaryContact(contact.isPrimaryContact())
            .notes(contact.getNotes())
            .build();
    }

    private <T> void reconcilePrimaryFlag(List<T> items,
                                          java.util.function.Predicate<T> isPrimary,
                                          java.util.function.BiConsumer<T, Boolean> setter) {
        if (items == null || items.isEmpty()) {
            return;
        }
        long primaryCount = items.stream().filter(isPrimary).count();
        if (primaryCount <= 1) {
            return;
        }
        // enforce only the most recently updated (last in list) retains primary flag
        for (int i = 0; i < items.size(); i++) {
            boolean keepPrimary = i == items.size() - 1;
            setter.accept(items.get(i), keepPrimary);
        }
    }

    private boolean isValidContactRequest(UserRecoveryContactRequestDTO dto) {
        if (dto == null || dto.getContactType() == null) {
            return false;
        }
        String value = dto.getContactValue();
        return value != null && !value.trim().isEmpty();
    }
}
