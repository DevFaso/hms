package com.example.hms.service.impl;

import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.OrderReviewType;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.pharmacy.partner.WithdrawnOrderPartnerHandler;
import com.example.hms.service.support.OrderReviewHoldGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("What a hold tells other people, after the event has committed")
class OrderReviewAfterCommitTest {

    private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
    private final WithdrawnOrderPartnerHandler partnerOffers = mock(WithdrawnOrderPartnerHandler.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final InBasketService inBasket = mock(InBasketService.class);
    private final OrderReviewAfterCommit bean = new OrderReviewAfterCommit(prescriptions, partnerOffers, assignments, inBasket);
    private final UUID hospital = UUID.randomUUID();

    @BeforeEach
    void messages() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
    }

    @AfterEach
    void reset() {
        OrderReviewHoldGuard.useHolds(null);
    }

    private OrderReviewAfterCommit.Notice notice(Set<UUID> recipients) {
        return new OrderReviewAfterCommit.Notice(hospital, UUID.randomUUID(), "A. Patient", UUID.randomUUID(),
            OrderReviewHoldServiceImpl.HELD_REFERENCE_TYPE, "order.review.notice.title", "order.review.notice.body",
            new Object[] {"A. Patient"}, recipients);
    }

    @Test
    @DisplayName("the orders' clinicians are told, urgently, and the hospital's doctors are not asked")
    void theClinicians() {
        UUID prescriber = UUID.randomUUID();
        bean.file(notice(Set.of(prescriber)));

        ArgumentCaptor<CreateInBasketItemRequest> item = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket).createItem(item.capture());
        assertThat(item.getValue().getRecipientUserId()).isEqualTo(prescriber);
        assertThat(item.getValue().getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(item.getValue().getHospitalId()).isEqualTo(hospital);
        assertThat(item.getValue().getBody()).contains("A. Patient");
        verify(assignments, never()).findLiveUsersAtHospitalWithRoleCodes(any(), any());
    }

    @Test
    @DisplayName("with no clinician known, the hospital's doctors: so hospital B learns of it without a refusal")
    void theFallback() {
        User doctor = new User();
        doctor.setId(UUID.randomUUID());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(eq(hospital), any())).thenReturn(List.of(doctor));

        bean.file(notice(Set.of()));

        ArgumentCaptor<CreateInBasketItemRequest> item = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket).createItem(item.capture());
        assertThat(item.getValue().getRecipientUserId()).isEqualTo(doctor.getId());
    }

    @Test
    @DisplayName("a recall reaches the partner while the hold stands, and not once a decision came first")
    void theRecall() {
        Prescription order = new Prescription();
        order.setId(UUID.randomUUID());
        when(prescriptions.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        bean.recallPartnerOffers(order.getId());
        verify(partnerOffers, never()).recallForReview(any());

        OrderReviewHoldGuard.useHolds((type, id) -> type == OrderReviewType.PRESCRIPTION && id.equals(order.getId()));
        bean.recallPartnerOffers(order.getId());
        verify(partnerOffers).recallForReview(order);
    }

    /** Sonar S6218: the body's arguments compare by content; the text form names no patient. */
    @Test
    void noticeComparesItsArgumentsByContent() {
        UUID patient = UUID.randomUUID();
        UUID reference = UUID.randomUUID();
        OrderReviewAfterCommit.Notice a = new OrderReviewAfterCommit.Notice(hospital, patient, "A. Patient", reference,
            "T", "title", "body", new Object[] {"A. Patient", 2}, Set.of());
        OrderReviewAfterCommit.Notice b = new OrderReviewAfterCommit.Notice(hospital, patient, "A. Patient", reference,
            "T", "title", "body", new Object[] {"A. Patient", 2}, Set.of());
        OrderReviewAfterCommit.Notice c = new OrderReviewAfterCommit.Notice(hospital, patient, "A. Patient", reference,
            "T", "title", "body", new Object[] {"A. Patient", 3}, Set.of());
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
        assertThat(a.toString()).doesNotContain("A. Patient").contains("bodyArgs=2");
    }
}
