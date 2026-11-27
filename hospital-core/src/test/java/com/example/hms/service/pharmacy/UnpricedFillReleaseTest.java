package com.example.hms.service.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Review of #1380 (F3, provisional UNPRICED_FILL_RELEASE): an unpriced fill leaves only on a logged urgency override
 * or an open stay, and the roles that set a price are told, never the person who handed over.
 */
@DisplayName("Review of #1380: the release of an unpriced fill and the notice to the price roles")
class UnpricedFillReleaseTest {

    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final UnpricedFillRelease release = new UnpricedFillRelease(assignments, notifications, transactions);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID pharmacist = UUID.randomUUID();

    private Pharmacy pharmacy() {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Pharmacy pharmacy = Pharmacy.builder().hospital(hospital).name("Réserve Médecine").build();
        pharmacy.setId(UUID.randomUUID());
        return pharmacy;
    }

    private static User user(UUID id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    @Test
    @DisplayName("an urgency reason or an open stay releases an unpriced fill; nothing else does")
    void onlyAnUrgencyOrAnOpenStayReleases() {
        assertThat(UnpricedFillRelease.releasable("Crise d'asthme", false)).isTrue();
        assertThat(UnpricedFillRelease.releasable(null, true)).isTrue();
        assertThat(UnpricedFillRelease.releasable(null, false)).isFalse();
        assertThat(UnpricedFillRelease.releasable("   ", false)).isFalse();
    }

    @Test
    @DisplayName("one subject per medicine and pharmacy, whatever the number of fills")
    void oneSubjectPerMedicineAndPharmacy() {
        UUID pharmacyId = UUID.randomUUID();
        UUID medicine = UUID.randomUUID();
        assertThat(UnpricedFillRelease.referenceOf(pharmacyId, medicine))
            .isEqualTo(UnpricedFillRelease.referenceOf(pharmacyId, medicine))
            .isNotEqualTo(UnpricedFillRelease.referenceOf(pharmacyId, UUID.randomUUID()));
    }

    @Test
    @DisplayName("the store managers and hospital admins are told, each once, never the person who handed over")
    void thePriceRolesAreToldNeverTheActor() {
        Pharmacy pharmacy = pharmacy();
        MedicationCatalogItem syrup = new MedicationCatalogItem();
        syrup.setId(UUID.randomUUID());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId, UnpricedFillRelease.PRICE_ROLE_CODES))
            .thenReturn(List.of(user(UUID.randomUUID(), "aminata.magasin"), user(pharmacist, "pharmacien.nuit"),
                user(UUID.randomUUID(), "aminata.magasin"), user(UUID.randomUUID(), " ")));

        release.priceNeeded(pharmacy, syrup, "Sirop de paracétamol", pharmacist);

        UUID subject = UnpricedFillRelease.referenceOf(pharmacy.getId(), syrup.getId());
        verify(notifications).createNotification(eq(UnpricedFillRelease.MESSAGE_KEY),
            eq(new Object[]{"Sirop de paracétamol", "Réserve Médecine"}), eq("aminata.magasin"),
            eq(UnpricedFillRelease.NOTIFICATION_TYPE), eq(subject));
        verify(notifications, never()).createNotification(any(), any(), eq("pharmacien.nuit"), any(), any());
        assertThat(UnpricedFillRelease.PRICE_ROLE_CODES).containsExactlyInAnyOrder("ROLE_STORE_MANAGER",
            "ROLE_HOSPITAL_ADMIN");
    }

    @Test
    @DisplayName("a failure to notify never undoes the fill")
    void aFailureToNotifyIsSwallowed() {
        Pharmacy pharmacy = pharmacy();
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any()))
            .thenReturn(List.of(user(UUID.randomUUID(), "aminata.magasin")));
        doThrow(new IllegalStateException("down")).when(notifications)
            .createNotification(any(), any(), any(), any(), any());
        assertThatCode(() -> release.priceNeeded(pharmacy, null, null, pharmacist)).doesNotThrowAnyException();

        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenThrow(new IllegalStateException("db"));
        assertThatCode(() -> release.priceNeeded(pharmacy, null, null, pharmacist)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a pharmacy with no hospital tells nobody")
    void noHospitalNoNotice() {
        release.priceNeeded(null, null, "x", pharmacist);
        release.priceNeeded(Pharmacy.builder().name("x").build(), null, "x", pharmacist);
        verifyNoInteractions(assignments, notifications);
    }

    @Test
    @DisplayName("pricing the item marks its notices read; nothing without both ids")
    void pricingMarksTheNoticesRead() {
        UUID pharmacyId = UUID.randomUUID();
        UUID medicine = UUID.randomUUID();
        release.priced(pharmacyId, medicine);
        verify(notifications).markReadForSubject(UnpricedFillRelease.referenceOf(pharmacyId, medicine),
            Set.of(UnpricedFillRelease.NOTIFICATION_TYPE));

        release.priced(null, medicine);
        release.priced(pharmacyId, null);
        verify(notifications).markReadForSubject(any(), any());
    }
}
