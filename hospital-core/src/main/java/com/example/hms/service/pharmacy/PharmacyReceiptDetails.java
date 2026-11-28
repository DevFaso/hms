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
        return of(payment, inventoryItemRepository, withPrices, false);
    }

    /**
     * QA D4186 (r81): the same receipt, marked as a duplicate when it is read again later. The price lines are the
     * ones kept on the payment ({@code chargedUnitPrice}); only a payment being recorded now, with none kept yet,
     * reads the list price.
     */
    public static PharmacyReceiptDetailsDTO of(PharmacyPayment payment, InventoryItemRepository inventoryItemRepository,
                                               boolean withPrices, boolean duplicate) {
        if (payment == null) {
            return null;
        }
        Dispense dispense = payment.getDispense();
        Pharmacy pharmacy = dispense != null ? dispense.getPharmacy() : null;
        PharmacyReceiptDetailsDTO.PharmacyReceiptDetailsDTOBuilder receipt = PharmacyReceiptDetailsDTO.builder()
                .patientMrn(mrnOf(payment))
                .patientName(payment.getPatient() != null ? payment.getPatient().getFullName() : null)
                .patientDateOfBirth(payment.getPatient() != null ? payment.getPatient().getDateOfBirth() : null)
                .duplicate(duplicate)
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
        Charged charged = chargedOf(payment, inventoryItemRepository, withPrices);
        if (charged != null) {
            receipt.unitPrice(charged.unitPrice()).lineTotal(charged.lineTotal());
            BigDecimal insurerShare = payment.getAmount() == null ? null
                    : charged.lineTotal().subtract(payment.getAmount());
            if (insurerShare != null && insurerShare.signum() > 0) {
                receipt.insurerShare(insurerShare);
            }
        }
        return receipt.build();
    }

    /** The unit price and line total of a payment's fill. */
    public record Charged(BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /**
     * QA D4186: what the payment charged: the figures kept on it; else, only while it is being recorded
     * ({@code readListPrice}), the pharmacy's list price read now. Null when neither is known.
     */
    public static Charged chargedOf(PharmacyPayment payment, InventoryItemRepository inventoryItemRepository,
                                    boolean readListPrice) {
        if (payment.getChargedUnitPrice() != null && payment.getChargedLineTotal() != null) {
            return new Charged(payment.getChargedUnitPrice(), payment.getChargedLineTotal());
        }
        Dispense dispense = payment.getDispense();
        Pharmacy pharmacy = dispense != null ? dispense.getPharmacy() : null;
        if (!readListPrice || pharmacy == null || dispense.getMedicationCatalogItem() == null
                || dispense.getQuantityDispensed() == null) {
            return null;
        }
        Optional<BigDecimal> unitPrice = PharmacyListPrice.of(pharmacy.getId(),
                dispense.getMedicationCatalogItem().getId(), inventoryItemRepository);
        return unitPrice.map(price -> new Charged(price,
                PharmacyListPrice.amount(price, dispense.getQuantityDispensed()))).orElse(null);
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
