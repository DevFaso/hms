package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Policy rule 6 (provisional, V345): an urgency override leaves a task for the review that must follow it, and a
 * third-party cover asked for leaves one for the check of its voucher. Never for the person who made it.
 */
@DisplayName("Deferred billing follow-up tasks (policy rule 6)")
class DispenseBillingDeferralFollowUpTest {

    private final InBasketService inBasket = mock(InBasketService.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final com.example.hms.service.NotificationService notifications =
            mock(com.example.hms.service.NotificationService.class);
    private final DispenseBillingDeferralFollowUp followUp =
            new DispenseBillingDeferralFollowUp(inBasket, assignments, notifications);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID pharmacist = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
    }

    private DispenseBillingDeferral deferral(DispenseBillingDeferralKind kind) {
        DispenseBillingDeferral row = DispenseBillingDeferral.builder().hospitalId(hospitalId)
                .prescriptionId(UUID.randomUUID()).patientId(UUID.randomUUID()).kind(kind)
                .status(DispenseBillingDeferralStatus.EXECUTED).reason("Crise").requestedBy(pharmacist)
                .requestedAt(LocalDateTime.now()).build();
        row.setId(UUID.randomUUID());
        return row;
    }

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    @Test
    @DisplayName("an urgency override is a task for each billing staff member of the hospital, never for whoever made it")
    void anOverrideIsATaskForBilling() {
        User billing = user(UUID.randomUUID());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId, Set.of("ROLE_BILLING_SPECIALIST")))
                .thenReturn(List.of(billing, user(pharmacist)));
        DispenseBillingDeferral override = deferral(DispenseBillingDeferralKind.URGENCY_OVERRIDE);

        followUp.reviewNeeded(override, "Awa Traoré", "Salbutamol");

        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket, times(1)).createItemInNewTransaction(items.capture());
        CreateInBasketItemRequest item = items.getValue();
        assertThat(item.getRecipientUserId()).isEqualTo(billing.getId());
        assertThat(item.getItemType()).isEqualTo(InBasketItemType.TASK);
        assertThat(item.getPriority()).isEqualTo(InBasketPriority.NORMAL);
        assertThat(item.getHospitalId()).isEqualTo(hospitalId);
        assertThat(item.getReferenceId()).isEqualTo(override.getId());
        assertThat(item.getReferenceType()).isEqualTo(DispenseBillingDeferralFollowUp.REFERENCE_TYPE);
        assertThat(item.getTitleKey()).isEqualTo("pharmacy.deferral.followUp.reviewTitle");
        assertThat(item.getTitle()).startsWith("Dérogation d");
        assertThat(item.getBody()).contains("Salbutamol", "Awa Traoré");
    }

    @Test
    @DisplayName("a third-party cover asked for is an urgent task for billing; a credit notifies whoever may grant it")
    void aThirdPartyCoverIsATaskAndACreditIsANotification() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any()))
                .thenReturn(List.of(user(UUID.randomUUID())));
        User accountant = user(UUID.randomUUID());
        accountant.setUsername("mariam.comptable");
        User admin = user(UUID.randomUUID());
        admin.setUsername("issa.admin");
        User asker = user(pharmacist);
        asker.setUsername("awa.pharmacienne");
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId,
                Set.of("ROLE_ACCOUNTANT", "ROLE_HOSPITAL_ADMIN"))).thenReturn(List.of(accountant, admin, asker));

        followUp.approvalNeeded(deferral(DispenseBillingDeferralKind.OUTPATIENT_CREDIT), "Awa", "Amoxicilline");
        verify(inBasket, never()).createItemInNewTransaction(any());
        // Review of #1244: accountants and hospital admins read no in-basket: each gets a notification, never
        // the person who asked.
        verify(notifications).createNotification(org.mockito.ArgumentMatchers.eq("pharmacy.deferral.followUp.creditRequested"),
                any(), org.mockito.ArgumentMatchers.eq("mariam.comptable"), org.mockito.ArgumentMatchers.eq("PHARMACY_BILLING_DEFERRAL"));
        verify(notifications).createNotification(any(), any(), org.mockito.ArgumentMatchers.eq("issa.admin"), any());
        verify(notifications, never()).createNotification(any(), any(), org.mockito.ArgumentMatchers.eq("awa.pharmacienne"), any());

        followUp.approvalNeeded(deferral(DispenseBillingDeferralKind.THIRD_PARTY_COVER), "Awa", "Amoxicilline");
        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket).createItemInNewTransaction(items.capture());
        assertThat(items.getValue().getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(items.getValue().getTitleKey()).isEqualTo("pharmacy.deferral.followUp.approvalTitle");
        verify(notifications, org.mockito.Mockito.times(2)).createNotification(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a failure to notify the credit approvers never reaches the caller")
    void aFailedNotificationIsSwallowed() {
        User accountant = user(UUID.randomUUID());
        accountant.setUsername("mariam.comptable");
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of(accountant));
        when(notifications.createNotification(any(), any(), any(), any())).thenThrow(new IllegalStateException("down"));

        followUp.approvalNeeded(deferral(DispenseBillingDeferralKind.OUTPATIENT_CREDIT), "Awa", "Amoxicilline");

        verify(notifications).createNotification(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a decision or a review closes the task; a failure to file never reaches the caller")
    void closingAndFailing() {
        DispenseBillingDeferral override = deferral(DispenseBillingDeferralKind.URGENCY_OVERRIDE);
        followUp.close(override);
        verify(inBasket).removeAllItemsForReference(override.getId(), DispenseBillingDeferralFollowUp.REFERENCE_TYPE);
        followUp.close(null);

        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenThrow(new IllegalStateException("db"));
        followUp.reviewNeeded(override, null, null);
        verify(inBasket, never()).createItemInNewTransaction(any());

        // No billing staff: nothing is filed, and the caller is not told off.
        org.mockito.Mockito.reset(assignments);
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of());
        followUp.reviewNeeded(override, "Awa", "Salbutamol");
        verify(inBasket, never()).createItemInNewTransaction(any());
    }
}
