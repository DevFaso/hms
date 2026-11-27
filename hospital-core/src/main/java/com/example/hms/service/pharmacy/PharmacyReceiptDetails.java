package com.example.hms.service.pharmacy;

import com.example.hms.mapper.pharmacy.DispenseMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacyPayment;
import com.example.hms.payload.dto.pharmacy.PharmacyReceiptDetailsDTO;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * QA D2310: the lines of a pharmacy receipt, read from the payment (see {@link PharmacyReceiptDetailsDTO}).
 * Called inside the payment service's transaction: the dispense, pharmacy, patient and cashier are LAZY.
 */
public final class PharmacyReceiptDetails {

    private PharmacyReceiptDetails() {
    }

    /**
     * @param withPrices the payment is being recorded now, so the list price read is the price charged;
     *                   {@code false} on a later read, which prints no price line rather than today's price
     */
    public static PharmacyReceiptDetailsDTO of(PharmacyPayment payment, InventoryItemRepository inventoryItemRepository,
                                               boolean withPrices) {
        if (payment == null) {
            return null;
        }
        Dispense dispense = payment.getDispense();
        Pharmacy pharmacy = dispense != null ? dispense.getPharmacy() : null;
        PharmacyReceiptDetailsDTO.PharmacyReceiptDetailsDTOBuilder receipt = PharmacyReceiptDetailsDTO.builder()
                .patientMrn(mrnOf(payment))
                .cashierName(DispenseMapper.displayNameOf(payment.getReceivedByUser()));
        if (dispense != null) {
            receipt.medicationName(dispense.getMedicationName())
                    .quantity(dispense.getQuantityDispensed())
                    .unit(dispense.getUnit());
        }
        if (pharmacy != null) {
            receipt.pharmacyName(pharmacy.getName())
                    .pharmacyAddress(addressOf(pharmacy))
                    .pharmacyPhone(StringUtils.hasText(pharmacy.getPhoneNumber()) ? pharmacy.getPhoneNumber().trim() : null);
        }
        if (withPrices && pharmacy != null && dispense.getMedicationCatalogItem() != null
                && dispense.getQuantityDispensed() != null) {
            Optional<BigDecimal> unitPrice = PharmacyListPrice.of(pharmacy.getId(),
                    dispense.getMedicationCatalogItem().getId(), inventoryItemRepository);
            if (unitPrice.isPresent()) {
                BigDecimal lineTotal = PharmacyListPrice.amount(unitPrice.get(), dispense.getQuantityDispensed());
                receipt.unitPrice(unitPrice.get()).lineTotal(lineTotal);
                BigDecimal insurerShare = payment.getAmount() == null ? null : lineTotal.subtract(payment.getAmount());
                if (insurerShare != null && insurerShare.signum() > 0) {
                    receipt.insurerShare(insurerShare);
                }
            }
        }
        return receipt.build();
    }

    /** The record number at the paying hospital; never another hospital's. */
    private static String mrnOf(PharmacyPayment payment) {
        Patient patient = payment.getPatient();
        UUID hospitalId = payment.getHospital() != null ? payment.getHospital().getId() : null;
        if (patient == null || hospitalId == null) {
            return null;
        }
        String mrn = patient.getMrnForHospital(hospitalId);
        return StringUtils.hasText(mrn) ? mrn : null;
    }

    private static String addressOf(Pharmacy pharmacy) {
        StringJoiner address = new StringJoiner(", ");
        for (String part : new String[] {pharmacy.getAddressLine1(), pharmacy.getAddressLine2(), pharmacy.getCity()}) {
            if (StringUtils.hasText(part)) {
                address.add(part.trim());
            }
        }
        return address.length() == 0 ? null : address.toString();
    }
}
