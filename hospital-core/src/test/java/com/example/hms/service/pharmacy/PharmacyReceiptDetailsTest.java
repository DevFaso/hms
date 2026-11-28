package com.example.hms.service.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacyPayment;
import com.example.hms.payload.dto.pharmacy.PharmacyReceiptDetailsDTO;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA D2310: receipt CHWF2396-PH-2026-000003 showed a medication, a patient name, a method and « 375 F CFA ».
 * Nothing said 15 units at 25 F, whose record it was, which pharmacy sold it or who took the money.
 */
@DisplayName("QA D2310: the lines of a pharmacy receipt")
class PharmacyReceiptDetailsTest {

    private final InventoryItemRepository inventory = mock(InventoryItemRepository.class);
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID otherHospitalId = UUID.randomUUID();
    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID catalogItemId = UUID.randomUUID();
    private PharmacyPayment payment;
    private Dispense dispense;

    private static Hospital hospital(UUID id) {
        Hospital hospital = new Hospital();
        hospital.setId(id);
        return hospital;
    }

    private static PatientHospitalRegistration registration(Hospital hospital, String mrn) {
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        // An id each: two unsaved registrations are otherwise one and the same in the patient's set.
        registration.setId(UUID.randomUUID());
        registration.setHospital(hospital);
        registration.setMrn(mrn);
        return registration;
    }

    @BeforeEach
    void aPaidFill() {
        Hospital hospital = hospital(hospitalId);
        Pharmacy pharmacy = new Pharmacy();
        pharmacy.setId(pharmacyId);
        pharmacy.setName("Pharmacie du CHU");
        pharmacy.setAddressLine1("Avenue Kwame Nkrumah");
        pharmacy.setCity("Ouagadougou");
        pharmacy.setPhoneNumber("+22625300000");
        MedicationCatalogItem catalogItem = new MedicationCatalogItem();
        catalogItem.setId(catalogItemId);
        dispense = new Dispense();
        dispense.setPharmacy(pharmacy);
        dispense.setMedicationCatalogItem(catalogItem);
        dispense.setMedicationName("Paracétamol 500 mg");
        dispense.setQuantityDispensed(new BigDecimal("15"));
        dispense.setUnit("comprimé");
        Patient patient = new Patient();
        patient.getHospitalRegistrations().add(registration(hospital(otherHospitalId), "OTHER-000009"));
        patient.getHospitalRegistrations().add(registration(hospital, "CHW-000123"));
        User cashier = new User();
        cashier.setFirstName("Awa");
        cashier.setLastName("Traoré");
        payment = new PharmacyPayment();
        payment.setDispense(dispense);
        payment.setPatient(patient);
        payment.setHospital(hospital);
        payment.setReceivedByUser(cashier);
        payment.setAmount(new BigDecimal("375"));
        InventoryItem priced = new InventoryItem();
        priced.setUnitPrice(new BigDecimal("25"));
        when(inventory.findByPharmacyIdAndMedicationCatalogItemId(pharmacyId, catalogItemId))
            .thenReturn(Optional.of(priced));
    }

    @Test
    @DisplayName("the checkout's answer carries quantity, unit price, record number, cashier and pharmacy")
    void everyLineOfTheReceipt() {
        PharmacyReceiptDetailsDTO receipt = PharmacyReceiptDetails.of(payment, inventory, true);

        assertThat(receipt.getMedicationName()).isEqualTo("Paracétamol 500 mg");
        assertThat(receipt.getQuantity()).isEqualByComparingTo("15");
        assertThat(receipt.getUnit()).isEqualTo("comprimé");
        assertThat(receipt.getUnitPrice()).isEqualByComparingTo("25");
        assertThat(receipt.getLineTotal()).isEqualByComparingTo("375");
        assertThat(receipt.getInsurerShare()).as("the patient paid the whole line").isNull();
        assertThat(receipt.getCashierName()).isEqualTo("Awa Traoré");
        assertThat(receipt.getPharmacyName()).isEqualTo("Pharmacie du CHU");
        assertThat(receipt.getPharmacyAddress()).isEqualTo("Avenue Kwame Nkrumah, Ouagadougou");
        assertThat(receipt.getPharmacyPhone()).isEqualTo("+22625300000");
    }

    @Test
    @DisplayName("the record number is the one of the hospital that took the payment, never another hospital's")
    void recordNumberOfThePayingHospital() {
        assertThat(PharmacyReceiptDetails.of(payment, inventory, true).getPatientMrn()).isEqualTo("CHW-000123");
    }

    @Test
    @DisplayName("what the insurer bears is the line less what the patient paid")
    void insurerShare() {
        payment.setAmount(new BigDecimal("75"));

        PharmacyReceiptDetailsDTO receipt = PharmacyReceiptDetails.of(payment, inventory, true);

        assertThat(receipt.getLineTotal()).isEqualByComparingTo("375");
        assertThat(receipt.getInsurerShare()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("a later read prints no price: today's list price is not what was charged then")
    void aLaterReadHasNoPriceLine() {
        PharmacyReceiptDetailsDTO receipt = PharmacyReceiptDetails.of(payment, inventory, false);

        assertThat(receipt.getUnitPrice()).isNull();
        assertThat(receipt.getLineTotal()).isNull();
        assertThat(receipt.getInsurerShare()).isNull();
        assertThat(receipt.getQuantity()).isEqualByComparingTo("15");
        assertThat(receipt.getCashierName()).isEqualTo("Awa Traoré");
        assertThat(receipt.getPatientMrn()).isEqualTo("CHW-000123");
        verifyNoInteractions(inventory);
    }

    @Test
    @DisplayName("QA D4186: a receipt reopened later prints the prices kept on the payment, marked as a duplicate, whatever the list price is today")
    void aReprintShowsThePricesFirstCharged() {
        payment.setChargedUnitPrice(new BigDecimal("25"));
        payment.setChargedLineTotal(new BigDecimal("375"));
        // The list price has changed since: it must not reach the reprint.
        InventoryItem repriced = new InventoryItem();
        repriced.setUnitPrice(new BigDecimal("40"));
        when(inventory.findByPharmacyIdAndMedicationCatalogItemId(pharmacyId, catalogItemId))
            .thenReturn(Optional.of(repriced));

        PharmacyReceiptDetailsDTO reprint = PharmacyReceiptDetails.of(payment, inventory, false, true);

        assertThat(reprint.isDuplicate()).isTrue();
        assertThat(reprint.getUnitPrice()).isEqualByComparingTo("25");
        assertThat(reprint.getLineTotal()).isEqualByComparingTo("375");
        assertThat(reprint.getPatientMrn()).isEqualTo("CHW-000123");
        assertThat(PharmacyReceiptDetails.of(payment, inventory, true).isDuplicate())
            .as("the answer to the payment being recorded is the original").isFalse();
        assertThat(PharmacyReceiptDetails.of(payment, inventory, true).getUnitPrice())
            .as("the kept price wins over the list price even while recording").isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("QA D4186: what a payment being recorded charges is the list price read now, to be kept on it")
    void thePriceToKeepIsReadWhenThePaymentIsTaken() {
        PharmacyReceiptDetails.Charged charged = PharmacyReceiptDetails.chargedOf(payment, inventory, true);

        assertThat(charged.unitPrice()).isEqualByComparingTo("25");
        assertThat(charged.lineTotal()).isEqualByComparingTo("375");
        assertThat(PharmacyReceiptDetails.chargedOf(payment, inventory, false))
            .as("a payment taken before prices were kept has none to print").isNull();
    }

    @Test
    @DisplayName("an unpriced medication leaves the price lines out, not at zero")
    void unpricedHasNoPriceLine() {
        when(inventory.findByPharmacyIdAndMedicationCatalogItemId(pharmacyId, catalogItemId))
            .thenReturn(Optional.empty());

        PharmacyReceiptDetailsDTO receipt = PharmacyReceiptDetails.of(payment, inventory, true);

        assertThat(receipt.getUnitPrice()).isNull();
        assertThat(receipt.getLineTotal()).isNull();
    }
}
