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

    private final InBasketItemRepository inBasketItemRepository;
    private final UserRepository userRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientRepository patientRepository;
    private final EncounterRepository encounterRepository;
    private final InBasketMapper inBasketMapper;

    @Override
    public Page<InBasketItemDTO> getItems(UUID userId, UUID hospitalId,
                                           InBasketItemType type, InBasketItemStatus status,
                                           Pageable pageable) {
        return inBasketItemRepository
                .findByRecipientFiltered(userId, hospitalId, type, status, pageable)
                .map(inBasketMapper::toDto);
    }

    @Override
    public InBasketSummaryDTO getSummary(UUID userId, UUID hospitalId) {
        long totalUnread = inBasketItemRepository
                .countByRecipientUser_IdAndHospital_IdAndStatus(userId, hospitalId, InBasketItemStatus.UNREAD);

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
            throw new BusinessException("In-Basket item is already acknowledged");
        }

        item.setStatus(InBasketItemStatus.ACKNOWLEDGED);
        item.setAcknowledgedAt(LocalDateTime.now());
        item.setAcknowledgedBy(userId);
        if (item.getReadAt() == null) {
            item.setReadAt(LocalDateTime.now());
        }
        item = inBasketItemRepository.save(item);

        log.info("In-Basket item {} acknowledged by user {}", itemId, userId);
        return inBasketMapper.toDto(item);
    }

    @Override
    @Transactional
    public InBasketItemDTO createItem(CreateInBasketItemRequest request) {
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

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("error.hospital.notFound", request.getHospitalId()));

        InBasketItem item = InBasketItem.builder()
                .recipientUser(recipient)
                .hospital(hospital)
                .itemType(request.getItemType())
                .priority(request.getPriority())
                .title(request.getTitle())
                .body(request.getBody())
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

    private long countByType(UUID userId, UUID hospitalId, InBasketItemType type) {
        return inBasketItemRepository
                .countByRecipientUser_IdAndHospital_IdAndStatusAndItemType(
                        userId, hospitalId, InBasketItemStatus.UNREAD, type);
    }

    private InBasketItem findItemOwnedBy(UUID itemId, UUID userId) {
        InBasketItem item = inBasketItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("error.inBasketItem.notFound", itemId));
        if (!item.getRecipientUser().getId().equals(userId)) {
            throw new BusinessException("In-Basket item does not belong to the current user");
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
