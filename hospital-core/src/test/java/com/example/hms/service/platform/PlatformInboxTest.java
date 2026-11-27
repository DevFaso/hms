package com.example.hms.service.platform;

import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.InBasketService;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA D4308: nothing was ever addressed to the platform administrators' inbox.
 * Four things now are, each to every active platform administrator.
 */
@DisplayName("D4308: what reaches the platform administrators' inbox")
class PlatformInboxTest {

    private InBasketService inBasket;
    private UserRepository users;
    private PlatformInbox inbox;
    private final UUID firstAdmin = UUID.randomUUID();
    private final UUID secondAdmin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        inBasket = mock(InBasketService.class);
        users = mock(UserRepository.class);
        when(users.findActivePlatformAdministrators()).thenReturn(List.of(user(firstAdmin), user(secondAdmin)));
        inbox = new PlatformInbox(inBasket, users);
    }

    @AfterEach
    void tearDown() {
        MessageUtil.setMessageSource(null);
        HospitalContextHolder.clear();
    }

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        user.setFirstName("Admin");
        user.setLastName("Plateforme");
        return user;
    }

    private List<CreateInBasketItemRequest> filed(int expected) {
        ArgumentCaptor<CreateInBasketItemRequest> captor = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket, times(expected)).createItemInNewTransaction(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("a provider facility awaiting verification is a task for every platform administrator, with no hospital")
    void aProviderAwaitingVerificationIsFiledForEveryAdministrator() {
        UUID facility = UUID.randomUUID();

        inbox.providerAwaitingVerification(facility, "Pharmacie Test Bobo", "PH-BOBO");

        List<CreateInBasketItemRequest> items = filed(2);
        assertThat(items).extracting(CreateInBasketItemRequest::getRecipientUserId)
            .containsExactlyInAnyOrder(firstAdmin, secondAdmin);
        CreateInBasketItemRequest item = items.get(0);
        assertThat(item.getHospitalId()).isNull();
        assertThat(item.getItemType()).isEqualTo(InBasketItemType.TASK);
        assertThat(item.getReferenceId()).isEqualTo(facility);
        assertThat(item.getReferenceType()).isEqualTo(PlatformInbox.PROVIDER_VERIFICATION);
        assertThat(item.getTitleKey()).isEqualTo("inbox.platform.provider.title");
        assertThat(item.getTitle()).contains("Pharmacie Test Bobo").startsWith("Prestataire à vérifier");
        assertThat(item.getBody()).contains("PH-BOBO");
        // A second submission replaces the item of the first.
        verify(inBasket).removeAllItemsForReference(facility, PlatformInbox.PROVIDER_VERIFICATION);
    }

    @Test
    @DisplayName("once somebody verifies or refuses, the task leaves every inbox")
    void aDecisionRemovesTheOpenTask() {
        UUID facility = UUID.randomUUID();

        inbox.providerDecided(facility);

        verify(inBasket).removeOpenItemsForReference(facility, PlatformInbox.PROVIDER_VERIFICATION);
        verify(inBasket, never()).createItemInNewTransaction(any());
    }

    @Test
    @DisplayName("an integration failing repeatedly is one item per integration, organisation and 24-hour window")
    void anIntegrationFailingRepeatedlyIsFiledOncePerWindow() {
        UUID organization = UUID.randomUUID();
        LocalDateTime window = LocalDateTime.of(2026, 11, 27, 8, 0);

        inbox.integrationFailingRepeatedly("partner.nhis", organization, window, 3);
        inbox.integrationFailingRepeatedly("partner.nhis", organization, window, 3);
        inbox.integrationFailingRepeatedly("partner.nhis", organization, window.plusDays(1), 3);

        List<CreateInBasketItemRequest> items = filed(6);
        // Same window: the same reference, which the in-basket files once per recipient.
        assertThat(items.get(0).getReferenceId()).isEqualTo(items.get(2).getReferenceId());
        assertThat(items.get(4).getReferenceId()).isNotEqualTo(items.get(0).getReferenceId());
        assertThat(items.get(0).getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(items.get(0).getItemType()).isEqualTo(InBasketItemType.MESSAGE);
        assertThat(items.get(0).getTitle()).contains("NHIS").doesNotContain("partner.nhis");
        assertThat(items.get(0).getBody()).contains("3");
    }

    @Test
    @DisplayName("a webhook endpoint disabled after failures names its hospital")
    void aDisabledWebhookEndpointNamesItsHospital() {
        UUID endpoint = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();

        inbox.webhookEndpointDisabled(endpoint, hospital, "Clinique A", 5);

        CreateInBasketItemRequest item = filed(2).get(0);
        assertThat(item.getHospitalId()).isEqualTo(hospital);
        assertThat(item.getReferenceType()).isEqualTo(PlatformInbox.WEBHOOK_ENDPOINT_DISABLED);
        assertThat(item.getTitle()).contains("Clinique A");
        assertThat(item.getBody()).contains("5");
    }

    @Test
    @DisplayName("an inbox that cannot be written never fails the work it reports on")
    void aFailureToFileIsSwallowed() {
        doThrow(new IllegalStateException("database down")).when(inBasket).createItemInNewTransaction(any());

        inbox.webhookEndpointDisabled(UUID.randomUUID(), UUID.randomUUID(), "Clinique A", 5);

        verify(inBasket, times(2)).createItemInNewTransaction(any());
    }

    @Test
    @DisplayName("a hospital administrator's request arrives with its hospital, its sender and the message as typed")
    void aHospitalAdministratorsRequestIsFiled() {
        UUID hospitalId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        HospitalRepository hospitals = mock(HospitalRepository.class);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Clinique A");
        when(hospitals.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        User admin = user(requester);
        admin.setFirstName("Fatou");
        admin.setLastName("Sawadogo");
        when(users.findById(requester)).thenReturn(Optional.of(admin));
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId).permittedHospitalIds(Set.of(hospitalId)).build());
        PlatformRequestService service = new PlatformRequestService(inbox, hospitals, users);

        UUID id = service.send(requester, "  Plan  ", " Dix comptes de plus, s'il vous plaît. ");

        CreateInBasketItemRequest item = filed(2).get(0);
        assertThat(item.getReferenceId()).isEqualTo(id);
        assertThat(item.getReferenceType()).isEqualTo(PlatformInbox.PLATFORM_REQUEST);
        assertThat(item.getHospitalId()).isEqualTo(hospitalId);
        assertThat(item.getTitle()).contains("Clinique A").contains("Plan");
        assertThat(item.getBody()).isEqualTo("Dix comptes de plus, s'il vous plaît.");
        assertThat(item.getOrderingProviderName()).isEqualTo("Fatou Sawadogo");
    }

    @Test
    @DisplayName("a request needs a subject and a message within their limits, each refusal naming its field")
    void aRequestIsValidatedFieldByField() {
        PlatformRequestService service = new PlatformRequestService(inbox, mock(HospitalRepository.class), users);
        UUID requester = UUID.randomUUID();

        assertThatThrownBy(() -> service.send(requester, " ", ""))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getFieldErrors()).containsOnlyKeys("subject", "message"));
        assertThatThrownBy(() -> service.send(requester, "x".repeat(121), "message"))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getField()).isEqualTo("subject"));
        assertThatThrownBy(() -> service.send(requester, "Plan", "x".repeat(2001)))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getField()).isEqualTo("message"));
        verifyNoInteractions(inBasket);
    }
}
