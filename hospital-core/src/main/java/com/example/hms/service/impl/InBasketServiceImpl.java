package com.example.hms.service.impl;

import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.InBasketMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.clinical.InBasketItemDTO;
import com.example.hms.payload.dto.clinical.InBasketSummaryDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.InBasketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class InBasketServiceImpl implements InBasketService {

    /** The one kind of item a hospital administrator with no clinical role reads. */
    private static final String DISPUTE_REVIEW_TYPE = "DEATH_DISPUTE";

    /** The reference type {@code LabResultServiceImpl} files a released result under. */
    private static final String LAB_RESULT_REFERENCE_TYPE = "LAB_RESULT";

    /**
     * Scorecard K4: the roles that may acknowledge an urgent clinical item, held on the caller's own (never only
     * through the super-admin inheritance, never during a support session: {@link
     * com.example.hms.security.ClinicalActor#holdsOwnRole}). Every role an urgent clinical item is filed to is
     * here, so a recipient can always close its own item ({@code InBasketAcknowledgerGuardTest}): the ordering
     * clinician of a lab result, the obstetric alert's circle, the requester and the bedside nurse of a
     * transfusion, and the lab directors told of an incompatible crossmatch on a request with no requester.
     */
    static final java.util.Set<String> CLINICAL_ACKNOWLEDGERS = java.util.Set.of(
            "ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_RADIOLOGIST",
            "ROLE_ANESTHESIOLOGIST", "ROLE_LAB_DIRECTOR");

    private final InBasketItemRepository inBasketItemRepository;
    private final UserRepository userRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientRepository patientRepository;
    private final EncounterRepository encounterRepository;
    private final InBasketMapper inBasketMapper;
    /** K4: a critical lab result is acknowledged by its read-back, not by closing its in-basket item. */
    private final com.example.hms.repository.LabResultRepository labResultRepository;
    /** K4: whether that read-back is still due; a provider, so the in-basket never builds the alert service. */
    private final org.springframework.beans.factory.ObjectProvider<com.example.hms.service.CriticalValueNotificationService>
            criticalValues;

    @Override
    public Page<InBasketItemDTO> getItems(UUID userId, UUID hospitalId,
                                           InBasketItemType type, InBasketItemStatus status,
                                           Pageable pageable) {
        return inBasketItemRepository
                .findByRecipientFiltered(userId, hospitalId, type, status, pageable)
                .map(inBasketMapper::toDto);
    }

    @Override
    public Page<InBasketItemDTO> getDisputeReviewItems(UUID userId, UUID hospitalId, InBasketItemStatus status, Pageable pageable) {
        return inBasketItemRepository
                .findByRecipientAndReferenceType(userId, hospitalId, DISPUTE_REVIEW_TYPE, status, pageable)
                .map(inBasketMapper::toDto);
    }

    @Override
    public InBasketSummaryDTO getDisputeReviewSummary(UUID userId, UUID hospitalId) {
        long unread = inBasketItemRepository.countByRecipientAndReferenceType(
                userId, hospitalId, DISPUTE_REVIEW_TYPE, InBasketItemStatus.UNREAD);
        return InBasketSummaryDTO.builder().totalUnread(unread).taskUnread(unread).build();
    }

    @Override
    public void requireDisputeReviewItem(UUID itemId, UUID userId) {
        if (!DISPUTE_REVIEW_TYPE.equals(findItemOwnedBy(itemId, userId).getReferenceType())) {
            throw new com.example.hms.exception.ResourceNotFoundException("error.inBasketItem.notFound", itemId);
        }
    }

    @Override
    public InBasketSummaryDTO getSummary(UUID userId, UUID hospitalId) {
        // hospitalId may be null (super-admin global view) — the repository's
        // JPQL drops the hospital filter when it is, returning every item
        // addressed to the recipient regardless of tenant.
        long totalUnread = inBasketItemRepository
                .countByRecipientForSummary(userId, hospitalId, InBasketItemStatus.UNREAD);

        long resultUnread = countByType(userId, hospitalId, InBasketItemType.RESULT);
        long orderUnread = countByType(userId, hospitalId, InBasketItemType.ORDER);
        long messageUnread = countByType(userId, hospitalId, InBasketItemType.MESSAGE);
        long taskUnread = countByType(userId, hospitalId, InBasketItemType.TASK);

        return InBasketSummaryDTO.builder()
                .totalUnread(totalUnread)
                .resultUnread(resultUnread)
                .orderUnread(orderUnread)
                .messageUnread(messageUnread)
                .taskUnread(taskUnread)
                .build();
    }

    @Override
    @Transactional
    public InBasketItemDTO markAsRead(UUID itemId, UUID userId) {
        InBasketItem item = findItemOwnedBy(itemId, userId);

        if (item.getStatus() == InBasketItemStatus.UNREAD) {
            item.setStatus(InBasketItemStatus.READ);
            item.setReadAt(LocalDateTime.now());
            item = inBasketItemRepository.save(item);
        }

        return inBasketMapper.toDto(item);
    }

    @Override
    @Transactional
    public InBasketItemDTO acknowledge(UUID itemId, UUID userId) {
        InBasketItem item = findItemOwnedBy(itemId, userId);

        if (item.getStatus() == InBasketItemStatus.ACKNOWLEDGED) {
            throw new BusinessException("inbasket.acknowledgement.finished");
        }
        requireClinicalAcknowledger(item);
        requireReadBackOfCriticalResult(item);

        item.setStatus(InBasketItemStatus.ACKNOWLEDGED);
        item.setAcknowledgedAt(LocalDateTime.now());
        item.setAcknowledgedBy(userId);
        if (item.getReadAt() == null) {
            item.setReadAt(LocalDateTime.now());
        }
        item = inBasketItemRepository.save(item);

        log.info("In-Basket item {} acknowledged by user {}", itemId, userId);
        // K4: the write-audit row of this acknowledgement names the item and its patient (ids only); who and
        // when are the row's actor and time, and the item keeps acknowledgedBy / acknowledgedAt.
        com.example.hms.security.audit.WriteAuditDetail.describe((com.example.hms.enums.AuditEventType) null,
                item.getId(), item.getPatient() != null ? item.getPatient().getId() : null);
        return inBasketMapper.toDto(item);
    }

    @Override
    @Transactional
    public InBasketItemDTO createItem(CreateInBasketItemRequest request) {
        return fileItem(request);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public InBasketItemDTO createItemInNewTransaction(CreateInBasketItemRequest request) {
        return fileItem(request);
    }

    @Override
    @Transactional
    public int removeOpenItemsForReference(UUID referenceId, String referenceType) {
        if (referenceId == null || referenceType == null) {
            return 0;
        }
        int removed = inBasketItemRepository.deleteOpenByReference(referenceId, referenceType);
        if (removed > 0) {
            log.info("In-Basket: {} open item(s) removed for superseded reference {} ({})",
                removed, referenceId, referenceType);
        }
        return removed;
    }

    @Override
    @Transactional
    public int removeAllItemsForReference(UUID referenceId, String referenceType) {
        if (referenceId == null || referenceType == null) {
            return 0;
        }
        return inBasketItemRepository.deleteAllByReference(referenceId, referenceType);
    }

    /** The one body of both entry points; each brings its own transaction. */
    private InBasketItemDTO fileItem(CreateInBasketItemRequest request) {
        // Deduplicate — don't create a second item for the same reference + recipient
        if (request.getReferenceId() != null
                && inBasketItemRepository.existsByReferenceIdAndReferenceTypeAndRecipientUser_Id(
                        request.getReferenceId(), request.getReferenceType(), request.getRecipientUserId())) {
            log.debug("Duplicate in-basket item skipped: ref={} type={} user={}",
                    request.getReferenceId(), request.getReferenceType(), request.getRecipientUserId());
            return null;
        }

        User recipient = userRepository.findById(request.getRecipientUserId())
                .orElseThrow(() -> new ResourceNotFoundException("error.user.notFound", request.getRecipientUserId()));

        // QA D4308: a platform item names no hospital.
        Hospital hospital = request.getHospitalId() == null ? null
                : hospitalRepository.findClinicalById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("error.hospital.notFound", request.getHospitalId()));

        InBasketItem item = InBasketItem.builder()
                .recipientUser(recipient)
                .hospital(hospital)
                .itemType(request.getItemType())
                .priority(request.getPriority())
                .title(request.getTitle())
                .body(request.getBody())
                // QA D1053: keys and arguments, rendered in the reader's language.
                .titleKey(request.getTitleKey())
                .titleArgs(request.getTitleKey() != null
                        ? com.example.hms.service.i18n.NotificationLocalizer.storable(request.getTitleArgs()) : null)
                .bodyKey(request.getBodyKey())
                .bodyArgs(request.getBodyKey() != null
                        ? com.example.hms.service.i18n.NotificationLocalizer.storable(request.getBodyArgs()) : null)
                .referenceId(request.getReferenceId())
                .referenceType(request.getReferenceType())
                .patientName(request.getPatientName())
                .orderingProviderName(request.getOrderingProviderName())
                .build();

        resolveEncounter(request.getEncounterId(), item);
        resolvePatient(request.getPatientId(), item);

        item = inBasketItemRepository.save(item);
        log.info("In-Basket item created: id={} type={} recipient={}", item.getId(), item.getItemType(), recipient.getId());
        return inBasketMapper.toDto(item);
    }

    // ─── helpers ──────────────────────────────────────────────

    /**
     * Scorecard K4: an urgent clinical item (any CRITICAL item, an URGENT result) is acknowledged by somebody who
     * can act on it clinically. The recipient check alone let a person holding a clinical role at one moment
     * acknowledge it from a non-clinical one, a super-admin through the inherited roles, or a support session
     * acting as the clinician. A non-urgent item, or an urgent task addressed to an administrative reader (the
     * review of a disputed death, a billing deferral), keeps the recipient rule alone.
     */
    private static void requireClinicalAcknowledger(InBasketItem item) {
        if (!isUrgentClinical(item)) {
            return;
        }
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (!com.example.hms.security.ClinicalActor.holdsOwnRole(auth, CLINICAL_ACKNOWLEDGERS.toArray(String[]::new))) {
            throw new BusinessException("inBasket.acknowledge.clinicalRoleRequired");
        }
    }

    private static boolean isUrgentClinical(InBasketItem item) {
        com.example.hms.enums.InBasketPriority priority = item.getPriority();
        return priority == com.example.hms.enums.InBasketPriority.CRITICAL
                || (priority == com.example.hms.enums.InBasketPriority.URGENT
                    && item.getItemType() == InBasketItemType.RESULT);
    }

    /**
     * Scorecard K4: the in-basket item of a critical lab result could be closed with one click while the result
     * itself still waited for its read-back: the clinician saw « acknowledged », the escalation chain kept paging.
     * The read-back is the acknowledgement ({@code LabResultServiceImpl#acknowledgeResult}); until it is on the
     * record the item stays open.
     */
    private void requireReadBackOfCriticalResult(InBasketItem item) {
        if (labResultRepository == null || criticalValues == null || item.getReferenceId() == null
                || !LAB_RESULT_REFERENCE_TYPE.equals(item.getReferenceType())) {
            return;
        }
        com.example.hms.service.CriticalValueNotificationService critical = criticalValues.getIfAvailable();
        if (critical == null) {
            return;
        }
        // The acknowledge path's own rule: paged, no read-back yet, and a read-back still applies.
        labResultRepository.findById(item.getReferenceId())
                .filter(critical::readBackDue)
                .ifPresent(result -> {
                    throw new BusinessException("lab.result.criticalNeedsReadBack");
                });
    }

    private long countByType(UUID userId, UUID hospitalId, InBasketItemType type) {
        return inBasketItemRepository
                .countByRecipientAndTypeForSummary(
                        userId, hospitalId, InBasketItemStatus.UNREAD, type);
    }

    private InBasketItem findItemOwnedBy(UUID itemId, UUID userId) {
        InBasketItem item = inBasketItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("error.inBasketItem.notFound", itemId));
        if (!item.getRecipientUser().getId().equals(userId)) {
            throw new BusinessException("inbasket.item.otherOwner");
        }
        return item;
    }

    private void resolveEncounter(UUID encounterId, InBasketItem item) {
        if (encounterId != null) {
            encounterRepository.findById(encounterId).ifPresent(item::setEncounter);
        }
    }

    private void resolvePatient(UUID patientId, InBasketItem item) {
        if (patientId != null) {
            patientRepository.findById(patientId).ifPresent(item::setPatient);
        }
    }
}
