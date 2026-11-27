package com.example.hms.service.pharmacy;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockWitnessConfirmation;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D2679 (r65 → r77): « the admin is the only witness and never confirms ». The one copy of the witness rule
 * now holds both halves: a controlled movement is recorded only while the witness's own confirmation stands,
 * and the hospital administrator witnesses only where no pharmacy colleague can.
 */
@DisplayName("QA D2679: the controlled-stock witness confirms, and is pharmacy staff when one exists")
class ControlledStockWitnessTest {

    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ControlledWitnessConfirmation confirmation = mock(ControlledWitnessConfirmation.class);
    private final ControlledStockWitness rule = new ControlledStockWitness(assignments, users);

    private final UUID hospitalId = UUID.randomUUID();
    private final User performer = user();
    private final User secondPharmacist = user();
    private final User administrator = user();
    private InventoryItem morphine;

    /** The refusal's key, or its English text once another test of the same JVM has loaded the bundles. */
    private static boolean said(Throwable refusal, String key, String text) {
        String message = String.valueOf(refusal.getMessage());
        return message.contains(key) || message.contains(text);
    }

    private static User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }

    @BeforeEach
    void stock() {
        rule.setConfirmation(confirmation);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Pharmacy pharmacy = new Pharmacy();
        pharmacy.setHospital(hospital);
        MedicationCatalogItem catalogItem = new MedicationCatalogItem();
        catalogItem.setControlled(true);
        morphine = new InventoryItem();
        morphine.setPharmacy(pharmacy);
        morphine.setMedicationCatalogItem(catalogItem);
        for (User user : List.of(performer, secondPharmacist, administrator)) {
            when(users.findById(user.getId())).thenReturn(Optional.of(user));
            when(assignments.existsActiveWitness(user.getId(), hospitalId, ControlledStockWitness.WITNESS_ROLE_CODES))
                .thenReturn(true);
        }
    }

    /** The live holders of a pharmacy witness role at the hospital. */
    private void pharmacyStaff(User... staff) {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId,
            ControlledStockWitness.PHARMACY_WITNESS_ROLE_CODES)).thenReturn(List.of(staff));
    }

    private void confirmed(User witness) {
        when(confirmation.isConfirmed(witness.getId(), performer.getId(), hospitalId)).thenReturn(true);
    }

    @Test
    @DisplayName("a named witness who has not confirmed is refused: nothing is final on a name alone")
    void namedButUnconfirmedIsRefused() {
        pharmacyStaff(performer, secondPharmacist);

        assertThatThrownBy(() -> rule.resolve(secondPharmacist.getId(), morphine, performer))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "witnessedBy")
            .matches(e -> said(e, "witnessConfirmation.notConfirmed", "has not confirmed"));
    }

    @Test
    @DisplayName("a second pharmacist who confirmed witnesses the movement")
    void confirmedColleagueWitnesses() {
        pharmacyStaff(performer, secondPharmacist);
        confirmed(secondPharmacist);

        assertThat(rule.resolve(secondPharmacist.getId(), morphine, performer)).isSameAs(secondPharmacist);
    }

    @Test
    @DisplayName("a confirmation given to another performer confirms nothing for this one")
    void confirmationIsForOnePerformer() {
        pharmacyStaff(performer, secondPharmacist);
        when(confirmation.isConfirmed(secondPharmacist.getId(), UUID.randomUUID(), hospitalId)).thenReturn(true);

        assertThatThrownBy(() -> rule.resolve(secondPharmacist.getId(), morphine, performer))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("the administrator is not a witness while a pharmacy colleague can be")
    void administratorRefusedWhenAColleagueExists() {
        pharmacyStaff(performer, secondPharmacist);
        confirmed(administrator);

        assertThatThrownBy(() -> rule.resolve(administrator.getId(), morphine, performer))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "witnessedBy")
            .matches(e -> said(e, "witnessConfirmation.pharmacyColleagueAvailable", "pharmacy colleague is available"));
    }

    @Test
    @DisplayName("with one pharmacist, the administrator is the fallback witness, and still confirms")
    void administratorIsTheFallbackAndConfirms() {
        pharmacyStaff(performer);

        assertThatThrownBy(() -> rule.resolve(administrator.getId(), morphine, performer))
            .isInstanceOf(FieldBusinessException.class)
            .matches(e -> said(e, "witnessConfirmation.notConfirmed", "has not confirmed"));

        confirmed(administrator);
        assertThat(rule.resolve(administrator.getId(), morphine, performer)).isSameAs(administrator);
    }

    @Test
    @DisplayName("the performer never witnesses their own movement, confirmed or not")
    void neverSelf() {
        pharmacyStaff(performer, secondPharmacist);
        confirmed(performer);

        assertThatThrownBy(() -> rule.resolve(performer.getId(), morphine, performer))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("an item that is not controlled asks for no witness and no confirmation")
    void notControlledAsksNothing() {
        morphine.getMedicationCatalogItem().setControlled(false);

        assertThat(rule.resolve(secondPharmacist.getId(), morphine, performer)).isNull();
        verify(confirmation, never()).isConfirmed(any(), any(), any());
    }

    @Test
    @DisplayName("confirming checks who may witness before any secret is tried")
    void confirmingChecksEligibilityFirst() {
        pharmacyStaff(performer, secondPharmacist);
        StockWitnessConfirmation row = StockWitnessConfirmation.builder().witnessUserId(secondPharmacist.getId()).build();
        when(confirmation.confirm(secondPharmacist, performer, hospitalId, "s3cret")).thenReturn(row);

        assertThat(rule.confirmWitness(secondPharmacist.getId(), "s3cret", performer, hospitalId)).isSameAs(row);

        // The administrator, with a pharmacy colleague available: refused, and no secret is tried on their account.
        assertThatThrownBy(() -> rule.confirmWitness(administrator.getId(), "s3cret", performer, hospitalId))
            .isInstanceOf(FieldBusinessException.class);
        // Oneself.
        assertThatThrownBy(() -> rule.confirmWitness(performer.getId(), "s3cret", performer, hospitalId))
            .isInstanceOf(FieldBusinessException.class);
        // Someone with no witness role at this hospital.
        User stranger = user();
        when(assignments.existsActiveWitness(stranger.getId(), hospitalId, ControlledStockWitness.WITNESS_ROLE_CODES))
            .thenReturn(false);
        assertThatThrownBy(() -> rule.confirmWitness(stranger.getId(), "s3cret", performer, hospitalId))
            .isInstanceOf(FieldBusinessException.class);
        verify(confirmation, never()).confirm(administrator, performer, hospitalId, "s3cret");
        verify(confirmation, never()).confirm(performer, performer, hospitalId, "s3cret");
    }
}
