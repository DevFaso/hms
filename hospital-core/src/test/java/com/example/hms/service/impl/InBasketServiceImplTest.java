package com.example.hms.service.impl;

import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InBasketServiceImplTest {

    @Mock private InBasketItemRepository inBasketItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private InBasketMapper inBasketMapper;

    @InjectMocks
    private InBasketServiceImpl service;

    private UUID userId;
    private UUID hospitalId;
    private UUID itemId;
    private User user;
    private Hospital hospital;
    private InBasketItem item;
    private InBasketItemDTO dto;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        itemId = UUID.randomUUID();

        user = new User();
        user.setId(userId);

        hospital = Hospital.builder().build();
        hospital.setId(hospitalId);

        item = InBasketItem.builder()
                .recipientUser(user)
                .hospital(hospital)
                .itemType(InBasketItemType.RESULT)
                .priority(InBasketPriority.CRITICAL)
                .status(InBasketItemStatus.UNREAD)
                .title("CRITICAL: Troponin I")
                .build();
        item.setId(itemId);

        dto = InBasketItemDTO.builder()
                .id(itemId)
                .itemType("RESULT")
                .priority("CRITICAL")
                .status("UNREAD")
                .title("CRITICAL: Troponin I")
                .build();
    }

    // ─── getItems ────────────────────────────────────────────────

    @Nested
    class GetItems {

        @Test
        void returnsMappedPage() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<InBasketItem> page = new PageImpl<>(List.of(item));

            when(inBasketItemRepository.findByRecipientFiltered(userId, hospitalId, null, null, pageable))
                    .thenReturn(page);
            when(inBasketMapper.toDto(item)).thenReturn(dto);

            Page<InBasketItemDTO> result = service.getItems(userId, hospitalId, null, null, pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getTitle()).isEqualTo("CRITICAL: Troponin I");
        }
    }

    // ─── getSummary ──────────────────────────────────────────────

    @Nested
    class GetSummary {

        @Test
        void returnsCountsByType() {
            when(inBasketItemRepository.countByRecipientUser_IdAndHospital_IdAndStatus(
                    userId, hospitalId, InBasketItemStatus.UNREAD)).thenReturn(5L);
            when(inBasketItemRepository.countByRecipientUser_IdAndHospital_IdAndStatusAndItemType(
                    userId, hospitalId, InBasketItemStatus.UNREAD, InBasketItemType.RESULT)).thenReturn(3L);
            when(inBasketItemRepository.countByRecipientUser_IdAndHospital_IdAndStatusAndItemType(
                    userId, hospitalId, InBasketItemStatus.UNREAD, InBasketItemType.ORDER)).thenReturn(1L);
            when(inBasketItemRepository.countByRecipientUser_IdAndHospital_IdAndStatusAndItemType(
                    userId, hospitalId, InBasketItemStatus.UNREAD, InBasketItemType.MESSAGE)).thenReturn(0L);
            when(inBasketItemRepository.countByRecipientUser_IdAndHospital_IdAndStatusAndItemType(
                    userId, hospitalId, InBasketItemStatus.UNREAD, InBasketItemType.TASK)).thenReturn(1L);

            InBasketSummaryDTO summary = service.getSummary(userId, hospitalId);

            assertThat(summary.getTotalUnread()).isEqualTo(5);
            assertThat(summary.getResultUnread()).isEqualTo(3);
            assertThat(summary.getOrderUnread()).isEqualTo(1);
            assertThat(summary.getMessageUnread()).isZero();
            assertThat(summary.getTaskUnread()).isEqualTo(1);
        }
    }

    // ─── markAsRead ──────────────────────────────────────────────

    @Nested
    class MarkAsRead {

        @Test
        void setsStatusAndReadAt() {
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(inBasketItemRepository.save(any(InBasketItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(inBasketMapper.toDto(any(InBasketItem.class))).thenReturn(dto);

            service.markAsRead(itemId, userId);

            ArgumentCaptor<InBasketItem> captor = ArgumentCaptor.forClass(InBasketItem.class);
            verify(inBasketItemRepository).save(captor.capture());
            InBasketItem saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(InBasketItemStatus.READ);
            assertThat(saved.getReadAt()).isNotNull();
        }

        @Test
        void idempotentWhenAlreadyRead() {
            item.setStatus(InBasketItemStatus.READ);
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(inBasketMapper.toDto(item)).thenReturn(dto);

            service.markAsRead(itemId, userId);

            verify(inBasketItemRepository, never()).save(any());
        }

        @Test
        void throwsWhenNotFound() {
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.markAsRead(itemId, userId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void throwsWhenNotOwner() {
            UUID otherUserId = UUID.randomUUID();
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));

            assertThatThrownBy(() -> service.markAsRead(itemId, otherUserId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("does not belong");
        }
    }

    // ─── acknowledge ─────────────────────────────────────────────

    @Nested
    class Acknowledge {

        @Test
        void setsStatusAndAcknowledgedFields() {
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(inBasketItemRepository.save(any(InBasketItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(inBasketMapper.toDto(any(InBasketItem.class))).thenReturn(dto);

            service.acknowledge(itemId, userId);

            ArgumentCaptor<InBasketItem> captor = ArgumentCaptor.forClass(InBasketItem.class);
            verify(inBasketItemRepository).save(captor.capture());
            InBasketItem saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(InBasketItemStatus.ACKNOWLEDGED);
            assertThat(saved.getAcknowledgedAt()).isNotNull();
            assertThat(saved.getAcknowledgedBy()).isEqualTo(userId);
            assertThat(saved.getReadAt()).isNotNull();
        }

        @Test
        void throwsWhenAlreadyAcknowledged() {
            item.setStatus(InBasketItemStatus.ACKNOWLEDGED);
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));

            assertThatThrownBy(() -> service.acknowledge(itemId, userId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already acknowledged");
        }
    }

    // ─── createItem ──────────────────────────────────────────────

    @Nested
    class CreateItem {

        @Test
        void createsAndSavesItem() {
            CreateInBasketItemRequest request = CreateInBasketItemRequest.builder()
                    .recipientUserId(userId)
                    .hospitalId(hospitalId)
                    .itemType(InBasketItemType.RESULT)
                    .priority(InBasketPriority.CRITICAL)
                    .title("CRITICAL: Troponin I")
                    .body("Value: 2.5 ng/mL")
                    .referenceId(UUID.randomUUID())
                    .referenceType("LAB_RESULT")
                    .patientName("Jane Doe")
                    .orderingProviderName("Dr. Smith")
                    .build();

            when(inBasketItemRepository.existsByReferenceIdAndReferenceTypeAndRecipientUser_Id(
                    any(), any(), any())).thenReturn(false);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(inBasketItemRepository.save(any(InBasketItem.class))).thenAnswer(inv -> {
                InBasketItem saved = inv.getArgument(0);
                saved.setId(UUID.randomUUID());
                return saved;
            });
            when(inBasketMapper.toDto(any(InBasketItem.class))).thenReturn(dto);

            InBasketItemDTO result = service.createItem(request);

            assertThat(result).isNotNull();
            verify(inBasketItemRepository).save(any(InBasketItem.class));
        }

        @Test
        void skipsDuplicateItem() {
            UUID refId = UUID.randomUUID();
            CreateInBasketItemRequest request = CreateInBasketItemRequest.builder()
                    .recipientUserId(userId)
                    .hospitalId(hospitalId)
                    .itemType(InBasketItemType.RESULT)
                    .referenceId(refId)
                    .referenceType("LAB_RESULT")
                    .title("Duplicate")
                    .build();

            when(inBasketItemRepository.existsByReferenceIdAndReferenceTypeAndRecipientUser_Id(
                    refId, "LAB_RESULT", userId)).thenReturn(true);

            InBasketItemDTO result = service.createItem(request);

            assertThat(result).isNull();
            verify(inBasketItemRepository, never()).save(any());
        }

        @Test
        void throwsWhenUserNotFound() {
            CreateInBasketItemRequest request = CreateInBasketItemRequest.builder()
                    .recipientUserId(userId)
                    .hospitalId(hospitalId)
                    .itemType(InBasketItemType.RESULT)
                    .title("Test")
                    .build();

            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createItem(request))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
