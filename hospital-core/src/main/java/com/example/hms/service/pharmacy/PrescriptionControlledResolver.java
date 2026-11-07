package com.example.hms.service.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.Prescription;
import com.example.hms.repository.MedicationCatalogItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * QA D678: the one answer to "is this prescription a controlled substance".
 *
 * <p>Controlled when the prescriber ticked it OR the hospital's catalog item
 * it was written from is controlled (D654). The catalog flag used to be added
 * only when the prescription response was built: the column stayed false, so
 * pharmacist verification before administration, the chart review, the
 * medication history and the record PDF all treated catalog-controlled
 * morphine as an ordinary drug. Those callers ask this class.
 *
 * <p>Round 18: the prescriber's two-factor step now exists and keys on THIS
 * class: {@code PrescriptionServiceImpl#signPrescription} re-authenticates the
 * prescriber whenever {@link #isControlled} is true, and writes the proof the
 * {@link ControlledSubstanceGuard} checks.
 *
 * <p>The catalog is always read at the prescription's own hospital, never
 * another tenant's item with the same code.
 */
@Component
@RequiredArgsConstructor
public class PrescriptionControlledResolver {

    private final MedicationCatalogItemRepository catalogRepository;

    public boolean isControlled(Prescription prescription) {
        if (prescription == null) {
            return false;
        }
        if (ticked(prescription)) {
            return true;
        }
        Hospital hospital = prescription.getHospital();
        return catalogControlled(hospital == null ? null : hospital.getId(),
            prescription.getMedicationCatalogItemId(), prescription.getMedicationCode());
    }

    private static boolean ticked(Prescription prescription) {
        return prescription.isControlledSubstance();
    }

    /**
     * QA D1314: {@link #isControlled} for many orders in one statement. The ids
     * of those that are controlled.
     */
    public java.util.Set<UUID> controlledAmong(java.util.Collection<Prescription> orders) {
        java.util.List<CatalogControlledIndex.Order> keys = new java.util.ArrayList<>();
        for (Prescription order : orders) {
            if (order.getHospital() != null) {
                keys.add(new CatalogControlledIndex.Order(order.getHospital().getId(),
                    order.getMedicationCatalogItemId(), order.getMedicationCode()));
            }
        }
        CatalogControlledIndex index = CatalogControlledIndex.read(catalogRepository, keys);
        java.util.Set<UUID> controlled = new java.util.HashSet<>();
        for (Prescription order : orders) {
            Hospital hospital = order.getHospital();
            if (isControlled(order, hospital == null ? null : hospital.getId(), index)) {
                controlled.add(order.getId());
            }
        }
        return controlled;
    }

    private static boolean isControlled(Prescription order, UUID hospitalId, CatalogControlledIndex index) {
        return ticked(order) || index.controlled(hospitalId, order.getMedicationCatalogItemId(), order.getMedicationCode());
    }

    /** The catalog's flag for the item picked, or for the code, at {@code hospitalId}. */
    public boolean catalogControlled(UUID hospitalId, UUID catalogItemId, String medicationCode) {
        if (hospitalId == null) {
            return false;
        }
        // D654: the picked item decides; a UI-created item has no code.
        if (catalogItemId != null) {
            return catalogRepository.findByIdAndHospital_Id(catalogItemId, hospitalId)
                .map(MedicationCatalogItem::isControlled)
                .orElse(false);
        }
        if (medicationCode == null || medicationCode.isBlank()) {
            return false;
        }
        return catalogRepository.findByHospitalIdAndCode(hospitalId, medicationCode.trim())
            .map(MedicationCatalogItem::isControlled)
            .orElse(false);
    }
}
