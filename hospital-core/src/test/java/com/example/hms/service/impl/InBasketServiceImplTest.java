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
    @Mock private com.example.hms.repository.LabResultRepository labResultRepository;
    @Mock private org.springframework.beans.factory.ObjectProvider<com.example.hms.service.CriticalValueNotificationService>
        criticalValues;
    @Mock private com.example.hms.service.CriticalValueNotificationService criticalValueService;

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
        void anItemSomebodyElseAcknowledgedSaysWho() {
            // QA D4104: the doctor's copy of an alert the nurse acknowledged reads « acquittée par … ».
            Pageable pageable = PageRequest.of(0, 20);
            UUID nurseId = UUID.randomUUID();
            User nurse = new User();
            nurse.setId(nurseId);
            nurse.setFirstName("Test");
            nurse.setLastName("Infirmière A");
            dto.setStatus("ACKNOWLEDGED");
            dto.setAcknowledgedBy(nurseId);
            InBasketItemDTO own = InBasketItemDTO.builder().id(UUID.randomUUID()).status("ACKNOWLEDGED")
                .acknowledgedBy(userId).build();
            InBasketItem ownItem = InBasketItem.builder().recipientUser(user).hospital(hospital).build();
            when(inBasketItemRepository.findByRecipientFiltered(userId, hospitalId, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(item, ownItem)));
            when(inBasketMapper.toDto(item)).thenReturn(dto);
            when(inBasketMapper.toDto(ownItem)).thenReturn(own);
            when(userRepository.findAllById(java.util.Set.of(nurseId))).thenReturn(List.of(nurse));

            service.getItems(userId, hospitalId, null, null, pageable);

            assertThat(dto.isAcknowledgedByOther()).isTrue();
            assertThat(dto.getAcknowledgedByName()).isEqualTo("Test Infirmière A");
            assertThat(own.isAcknowledgedByOther()).isFalse();
            assertThat(own.getAcknowledgedByName()).isNull();
        }

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
            when(inBasketItemRepository.countByRecipientForSummary(
                    userId, hospitalId, InBasketItemStatus.UNREAD)).thenReturn(5L);
            when(inBasketItemRepository.countByRecipientAndTypeForSummary(
                    userId, hospitalId, InBasketItemStatus.UNREAD, InBasketItemType.RESULT)).thenReturn(3L);
            when(inBasketItemRepository.countByRecipientAndTypeForSummary(
                    userId, hospitalId, InBasketItemStatus.UNREAD, InBasketItemType.ORDER)).thenReturn(1L);
            when(inBasketItemRepository.countByRecipientAndTypeForSummary(
                    userId, hospitalId, InBasketItemStatus.UNREAD, InBasketItemType.MESSAGE)).thenReturn(0L);
            when(inBasketItemRepository.countByRecipientAndTypeForSummary(
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
                    .satisfies(error -> assertThat(error).extracting("messageKey").isEqualTo("inbasket.item.otherOwner"));
        }
    }

    // ─── acknowledge ─────────────────────────────────────────────

    @Nested
    class Acknowledge {

        @org.junit.jupiter.api.AfterEach
        void clearAuthentication() {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        private void signInAs(String... roles) {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("u", "p",
                    java.util.Arrays.stream(roles)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
        }

        @Test
        void acknowledgingAnUrgentAlertItemTellsTheLadderSoEveryCopyIsAnswered() {
            // QA D4104: one acknowledgement answers the alert for every recipient, at once.
            signInAs("ROLE_NURSE");
            UUID alertId = UUID.randomUUID();
            item.setItemType(InBasketItemType.MESSAGE);
            item.setReferenceId(alertId);
            item.setReferenceType(com.example.hms.service.maternity.ObstetricAlertEscalationWorker.REFERENCE_TYPE);
            com.example.hms.service.maternity.ObstetricAlertEscalationWorker ladder =
                org.mockito.Mockito.mock(com.example.hms.service.maternity.ObstetricAlertEscalationWorker.class);
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<com.example.hms.service.maternity.ObstetricAlertEscalationWorker>
                provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(ladder);
            service.setUrgentAlertLadder(provider);
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(inBasketItemRepository.save(any(InBasketItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(inBasketMapper.toDto(any(InBasketItem.class))).thenReturn(dto);

            service.acknowledge(itemId, userId);

            verify(ladder).itemAcknowledged(alertId);
        }

        @Test
        void acknowledgingAnyOtherItemLeavesTheLadderAlone() {
            signInAs("ROLE_DOCTOR");
            item.setReferenceId(UUID.randomUUID());
            item.setReferenceType("IMAGING_REPORT");
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<com.example.hms.service.maternity.ObstetricAlertEscalationWorker>
                provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            service.setUrgentAlertLadder(provider);
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(inBasketItemRepository.save(any(InBasketItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(inBasketMapper.toDto(any(InBasketItem.class))).thenReturn(dto);

            service.acknowledge(itemId, userId);

            org.mockito.Mockito.verifyNoInteractions(provider);
        }

        @Test
        void setsStatusAndAcknowledgedFields() {
            signInAs("ROLE_DOCTOR");
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
        void k4_aCriticalItemIsNotAcknowledgedFromANonClinicalRole() {
            signInAs("ROLE_RECEPTIONIST");
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));

            assertThatThrownBy(() -> service.acknowledge(itemId, userId))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey())
                        .isEqualTo("inBasket.acknowledge.clinicalRoleRequired"));
            verify(inBasketItemRepository, never()).save(any());
        }

        @Test
        void k4_aSuperAdminDoesNotAcknowledgeACriticalItemThroughTheInheritedRoles() {
            signInAs("ROLE_SUPER_ADMIN", "ROLE_DOCTOR", "ROLE_NURSE");
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));

            assertThatThrownBy(() -> service.acknowledge(itemId, userId)).isInstanceOf(BusinessException.class);
            verify(inBasketItemRepository, never()).save(any());
        }

        @Test
        void k4_aRoutineItemKeepsTheRecipientRuleAlone() {
            signInAs("ROLE_BILLING_SPECIALIST");
            item.setItemType(InBasketItemType.TASK);
            item.setPriority(InBasketPriority.URGENT);
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(inBasketItemRepository.save(any(InBasketItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(inBasketMapper.toDto(any(InBasketItem.class))).thenReturn(dto);

            service.acknowledge(itemId, userId);

            assertThat(item.getStatus()).isEqualTo(InBasketItemStatus.ACKNOWLEDGED);
        }

        @Test
        void k4_theItemOfACriticalLabResultStaysOpenUntilItsReadBack() {
            signInAs("ROLE_DOCTOR");
            UUID resultId = UUID.randomUUID();
            item.setReferenceId(resultId);
            item.setReferenceType("LAB_RESULT");
            com.example.hms.model.LabResult result = new com.example.hms.model.LabResult();
            result.setCriticalNotifiedAt(java.time.LocalDateTime.now());
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(labResultRepository.findById(resultId)).thenReturn(Optional.of(result));
            when(criticalValues.getIfAvailable()).thenReturn(criticalValueService);
            when(criticalValueService.readBackDue(result)).thenReturn(true);

            assertThatThrownBy(() -> service.acknowledge(itemId, userId))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey())
                        .isEqualTo("lab.result.criticalNeedsReadBack"));
            verify(inBasketItemRepository, never()).save(any());

            // Read back (or amended after its read-back, or no longer critical): the item can be closed.
            when(criticalValueService.readBackDue(result)).thenReturn(false);
            when(inBasketItemRepository.save(any(InBasketItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(inBasketMapper.toDto(any(InBasketItem.class))).thenReturn(dto);
            service.acknowledge(itemId, userId);
            assertThat(item.getStatus()).isEqualTo(InBasketItemStatus.ACKNOWLEDGED);
            assertThat(item.getAcknowledgedBy()).isEqualTo(userId);
        }

        @Test
        void k4_aLabDirectorClosesTheIncompatibleCrossmatchItemFiledToThem() {
            // TransfusionServiceImpl files an URGENT RESULT to the lab directors when the emergency
            // request has no requester: they must be able to close it.
            signInAs("ROLE_LAB_DIRECTOR");
            item.setPriority(InBasketPriority.URGENT);
            item.setReferenceType("TRANSFUSION_CROSSMATCH");
            item.setReferenceId(UUID.randomUUID());
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(inBasketItemRepository.save(any(InBasketItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(inBasketMapper.toDto(any(InBasketItem.class))).thenReturn(dto);

            service.acknowledge(itemId, userId);

            assertThat(item.getStatus()).isEqualTo(InBasketItemStatus.ACKNOWLEDGED);
            assertThat(item.getAcknowledgedBy()).isEqualTo(userId);
        }

        @Test
        void throwsWhenAlreadyAcknowledged() {
            item.setStatus(InBasketItemStatus.ACKNOWLEDGED);
            when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));

            assertThatThrownBy(() -> service.acknowledge(itemId, userId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(error).extracting("messageKey").isEqualTo("inbasket.acknowledgement.finished"));
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
            when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
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

    /** The exception to E9 #67: an administrator touches only the dispute review addressed to them. */
    @Test
    void aDisputeReviewItemIsOnlyTheOneAddressedToThem() {
        UUID me = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        com.example.hms.model.User recipient = new com.example.hms.model.User();
        recipient.setId(me);
        InBasketItem item = new InBasketItem();
        item.setRecipientUser(recipient);
        item.setReferenceType("DEATH_DISPUTE");
        when(inBasketItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        service.requireDisputeReviewItem(itemId, me);

        item.setReferenceType("LAB_RESULT");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireDisputeReviewItem(itemId, me))
            .isInstanceOf(ResourceNotFoundException.class);

        item.setReferenceType("DEATH_DISPUTE");
        UUID other = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireDisputeReviewItem(itemId, other))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
    }
}
