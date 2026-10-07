package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.repository.pharmacy.DispenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * G15 AC-12: which of a patient's prescriptions has a fill waiting at a
 * pharmacy counter, for the medication reads (the patient portal, the proxy
 * view, the apps, and the staff medication list). One query per page.
 *
 * <p>A withdrawn prescription never reports readiness: its withdrawal voided
 * the preparation in the same transaction, and this read does not trust a
 * stale row to say otherwise.
 *
 * <p>Only called from inside a read that has already passed its own access
 * gate (own record, verified proxy, or chart access); it adds no gate of its
 * own and reveals nothing beyond the rows it is given.
 */
@Component
@RequiredArgsConstructor
public class ReadyForCollectionLookup {

    private final DispenseRepository dispenseRepository;

    /** When the fill was marked ready, and at which pharmacy. */
    public record Readiness(LocalDateTime readyAt, String pharmacyName) {
    }

    /** The open preparations of these prescriptions, keyed by prescription id. */
    public Map<UUID, Readiness> openPreparations(Collection<Prescription> prescriptions) {
        List<UUID> live = prescriptions.stream()
                .filter(p -> p.getId() != null)
                .filter(p -> p.getStatus() == null || !p.getStatus().isWithdrawn())
                .map(Prescription::getId)
                .toList();
        if (live.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Readiness> ready = new HashMap<>();
        for (Dispense d : dispenseRepository.findByPrescription_IdInAndStatus(live, DispenseStatus.PENDING)) {
            if (d.getPrescription() != null) {
                ready.putIfAbsent(d.getPrescription().getId(), new Readiness(d.getCreatedAt(),
                        d.getPharmacy() != null ? d.getPharmacy().getName() : null));
            }
        }
        return ready;
    }
}
