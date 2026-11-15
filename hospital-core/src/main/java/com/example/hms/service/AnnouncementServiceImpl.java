package com.example.hms.service;

import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.utility.RoleValidator;
import com.example.hms.model.Announcement;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AnnouncementResponseDTO;
import com.example.hms.repository.AnnouncementRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {
    private static final String ANNOUNCEMENT_NOT_FOUND_KEY = "announcement.notFound";
    private final AnnouncementRepository announcementRepository;
    private final HospitalRepository hospitalRepository;
    private final NotificationService notificationService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponseDTO> getAnnouncements(int limit) {
        int effectiveLimit = Math.clamp(limit, 1, 100);
        UUID hospitalId = currentHospitalId();
        if (hospitalId == null && !HospitalContextHolder.getContextOrEmpty().isGlobalView()) {
            // No hospital to read at (several held and none named, or none held):
            // no hospital's announcements, never every hospital's.
            return List.of();
        }
        List<Announcement> announcements = hospitalId != null
            ? announcementRepository.findByHospital_IdOrderByDateDesc(
                hospitalId,
                PageRequest.of(0, effectiveLimit, Sort.by(Sort.Direction.DESC, "date"))
            ).getContent()
            : announcementRepository.findAll().stream()
                .sorted((a, b) -> b.getDate().compareTo(a.getDate()))
                .limit(effectiveLimit)
                .toList();

        return announcements.stream()
            .map(this::toDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AnnouncementResponseDTO getAnnouncement(UUID id) {
        Announcement announcement = announcementRepository.findById(id)
            .orElseThrow(() -> new com.example.hms.exception.ResourceNotFoundException(ANNOUNCEMENT_NOT_FOUND_KEY, id));
        requireReadable(announcement, id);
        return toDTO(announcement);
    }

    /**
     * Another hospital's announcement answers as an unknown id (404), under the same guard as the writes. A
     * platform-wide one (no hospital) is for everybody. A caller with no hospital to act at (several held and
     * none named, a patient) holds nobody's: the list answers them nothing, and this says no more than it does.
     */
    private static void requireReadable(Announcement announcement, UUID id) {
        if (announcement.getHospital() == null) {
            return;
        }
        try {
            com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(
                announcement.getHospital(), ANNOUNCEMENT_NOT_FOUND_KEY, id);
        } catch (BusinessException | com.example.hms.exception.HospitalScopeRefusedException noScope) {
            throw new com.example.hms.exception.ResourceNotFoundException(ANNOUNCEMENT_NOT_FOUND_KEY, id);
        }
    }

    @Override
    @Transactional
    public AnnouncementResponseDTO createAnnouncement(String text) {
        requireText(text);
        UUID hospitalId = currentHospitalId();
        if (hospitalId == null && !HospitalContextHolder.getContextOrEmpty().isGlobalView()) {
            // Only a super-admin in global view posts a platform-wide announcement.
            throw new BusinessException(RoleValidator.HOSPITAL_CONTEXT_REQUIRED);
        }
        Hospital hospital = hospitalId != null ? hospitalRepository.getReferenceById(hospitalId) : null;
        Announcement announcement = Announcement.builder()
            .text(text)
            .date(LocalDateTime.now())
            .hospital(hospital)
            .build();
        AnnouncementResponseDTO saved = toDTO(announcementRepository.save(announcement));

        // One key for every recipient, rendered in each reader's language
        // when read (QA D665). The announcement text itself is shown as typed.
        // QA D1610: a hospital's announcement reaches that hospital's staff
        // (an active, non-patient assignment there) and nobody else; only a
        // super-admin's platform-wide one (global view, no hospital) reaches
        // the staff of every hospital. Patients are never recipients.
        Object[] notificationArgs = {text};
        List<User> recipients = hospitalId != null
            ? assignmentRepository.findLiveStaffUsersAtHospital(hospitalId)
            : assignmentRepository.findLiveStaffUsersPlatformWide();
        for (User user : recipients) {
            try {
                notificationService.createNotification("announcement.new.notification", notificationArgs,
                    user.getUsername(), "ANNOUNCEMENT");
            } catch (Exception e) {
                log.warn("Failed to notify user {}: {}", user.getUsername(), e.getMessage());
            }
        }

        return saved;
    }

    @Override
    @Transactional
    public AnnouncementResponseDTO updateAnnouncement(UUID id, String text) {
        Announcement announcement = announcementRepository.findById(id)
            .orElseThrow(() -> new com.example.hms.exception.ResourceNotFoundException(ANNOUNCEMENT_NOT_FOUND_KEY, id));
        // QA r5 tenancy sweep: a write loads under the tenant guard.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(announcement.getHospital(), ANNOUNCEMENT_NOT_FOUND_KEY, id);
        requireText(text);
        announcement.setText(text);
        return toDTO(announcementRepository.save(announcement));
    }

    @Override
    @Transactional
    public void deleteAnnouncement(UUID id) {
        // QA r5 tenancy sweep: a delete loads under the tenant guard first.
        Announcement announcement = announcementRepository.findById(id)
            .orElseThrow(() -> new com.example.hms.exception.ResourceNotFoundException(ANNOUNCEMENT_NOT_FOUND_KEY, id));
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(announcement.getHospital(), ANNOUNCEMENT_NOT_FOUND_KEY, id);
        announcementRepository.delete(announcement);
    }

    /** QA D1615: a blank announcement is refused, naming the field, before anything is stored or sent. */
    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new FieldBusinessException("text", "announcement.text.required");
        }
    }

    private AnnouncementResponseDTO toDTO(Announcement announcement) {
        return new AnnouncementResponseDTO(
            announcement.getId(),
            announcement.getText(),
            announcement.getDate()
        );
    }

    /** The hospital this request is pinned to; {@code null} in global view or with none. */
    private UUID currentHospitalId() {
        return ActingScopeResolver.pinnedHospitalIdOrNull();
    }
}
