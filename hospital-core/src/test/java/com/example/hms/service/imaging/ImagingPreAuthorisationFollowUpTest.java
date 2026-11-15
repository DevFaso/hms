package com.example.hms.service.imaging;

import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.ImagingModality;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.support.ImagingPreAuthorisationPolicy.FollowUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

/** Provisional rule 1 (2026-11-15): the billing follow-up task, filed and closed. */
class ImagingPreAuthorisationFollowUpTest {

    private final InBasketService inBasket = mock(InBasketService.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final ImagingPreAuthorisationFollowUp followUp = new ImagingPreAuthorisationFollowUp(inBasket, assignments);
    private ImagingOrder order;
    private UUID hospitalId;

    @BeforeEach
    void setUp() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(hospital);
        order.setModality(ImagingModality.CT);
        order.setStudyType("Scanner cérébral");
    }

    @AfterEach
    void tearDown() {
        com.example.hms.utility.MessageUtil.setMessageSource(null);
    }

    @Test
    void anOpenedFollowUpIsATaskForEachBillingStaffMemberOfTheHospital() {
        User first = user();
        User second = user();
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId, Set.of("ROLE_BILLING_SPECIALIST")))
            .thenReturn(List.of(first, second));

        followUp.apply(order, FollowUp.OPENED);

        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket, times(2)).createItemInNewTransaction(items.capture());
        assertThat(items.getAllValues()).extracting(CreateInBasketItemRequest::getRecipientUserId)
            .containsExactly(first.getId(), second.getId());
        CreateInBasketItemRequest item = items.getValue();
        assertThat(item.getItemType()).isEqualTo(InBasketItemType.TASK);
        assertThat(item.getHospitalId()).isEqualTo(hospitalId);
        assertThat(item.getReferenceId()).isEqualTo(order.getId());
        assertThat(item.getReferenceType()).isEqualTo(ImagingPreAuthorisationFollowUp.REFERENCE_TYPE);
        assertThat(item.getTitleKey()).isEqualTo("imaging.preauth.followUp.title");
        assertThat(item.getTitle()).startsWith("Préautorisation à obtenir");
        assertThat(item.getBody()).contains("Scanner cérébral");
    }

    @Test
    void aClosedFollowUpRemovesTheTasksAndNothingElseIsFiled() {
        followUp.apply(order, FollowUp.CLOSED);
        verify(inBasket).removeAllItemsForReference(order.getId(), ImagingPreAuthorisationFollowUp.REFERENCE_TYPE);
        verify(inBasket, never()).createItemInNewTransaction(any());

        followUp.apply(order, FollowUp.UNCHANGED);
        verify(inBasket, never()).createItemInNewTransaction(any());
    }

    @Test
    void aFailureToFileNeverReachesTheCaller() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenThrow(new IllegalStateException("db"));
        followUp.apply(order, FollowUp.OPENED);
        verify(inBasket, never()).createItemInNewTransaction(any());
    }

    private static User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }
}
