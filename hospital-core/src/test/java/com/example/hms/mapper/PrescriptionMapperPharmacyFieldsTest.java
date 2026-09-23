package com.example.hms.mapper;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G7 — the response carries the pharmacy, the dispatch state and the latest
 * pharmacy event, and G5 — the clarification exchange.
 */
@DisplayName("PrescriptionMapper: pharmacy fields")
class PrescriptionMapperPharmacyFieldsTest {

    private final PrescriptionMapper mapper = new PrescriptionMapper();

    private static Prescription base() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setMedicationName("Amoxicilline 500 mg");
        return p;
    }

    @Test
    @DisplayName("pharmacy identity, contact and dispatch state are projected")
    void pharmacyAndDispatchFields() {
        Prescription p = base();
        UUID pharmacyId = UUID.randomUUID();
        LocalDateTime dispatchedAt = LocalDateTime.of(2026, 9, 22, 9, 0);
        p.setPharmacyId(pharmacyId);
        p.setPharmacyName("Pharmacie du Marché");
        p.setPharmacyContact("+22670000000");
        p.setDispatchChannel("SMS");
        p.setDispatchStatus("SENT");
        p.setDispatchedAt(dispatchedAt);
        p.setStatus(PrescriptionStatus.TRANSMITTED);

        PrescriptionResponseDTO dto = mapper.toResponseDTO(p);

        assertThat(dto.getPharmacyId()).isEqualTo(pharmacyId);
        assertThat(dto.getPharmacyName()).isEqualTo("Pharmacie du Marché");
        assertThat(dto.getPharmacyContact()).isEqualTo("+22670000000");
        assertThat(dto.getDispatchChannel()).isEqualTo("SMS");
        assertThat(dto.getDispatchStatus()).isEqualTo("SENT");
        assertThat(dto.getDispatchedAt()).isEqualTo(dispatchedAt);
        assertThat(dto.getLastPharmacyEvent()).isEqualTo("TRANSMITTED");
        assertThat(dto.getLastPharmacyEventAt()).isEqualTo(dispatchedAt);
    }

    @Test
    @DisplayName("a prescriber-owned status reports no pharmacy event")
    void noPharmacyEventWhileSigned() {
        Prescription p = base();
        p.setStatus(PrescriptionStatus.SIGNED);

        PrescriptionResponseDTO dto = mapper.toResponseDTO(p);

        assertThat(dto.getLastPharmacyEvent()).isNull();
        assertThat(dto.getLastPharmacyEventAt()).isNull();
        assertThat(dto.getPharmacyId()).isNull();
    }

    @Test
    @DisplayName("a pharmacy-owned status is the latest event, stamped with the row's last update")
    void pharmacyStatusIsTheEvent() {
        Prescription p = base();
        p.setStatus(PrescriptionStatus.PARTNER_REJECTED);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 22, 11, 0);
        p.setUpdatedAt(updatedAt);

        PrescriptionResponseDTO dto = mapper.toResponseDTO(p);

        assertThat(dto.getLastPharmacyEvent()).isEqualTo("PARTNER_REJECTED");
        assertThat(dto.getLastPharmacyEventAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("the clarification exchange is projected, and its request instant is the event instant")
    void clarificationFields() {
        Prescription p = base();
        LocalDateTime askedAt = LocalDateTime.of(2026, 9, 22, 10, 30);
        p.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);
        p.setClarificationReason("Dose au-dessus du plafond rénal");
        p.setClarificationRequestedAt(askedAt);
        p.setUpdatedAt(askedAt.plusMinutes(5));

        PrescriptionResponseDTO dto = mapper.toResponseDTO(p);

        assertThat(dto.getClarificationReason()).isEqualTo("Dose au-dessus du plafond rénal");
        assertThat(dto.getClarificationRequestedAt()).isEqualTo(askedAt);
        assertThat(dto.getClarificationResponse()).isNull();
        assertThat(dto.getClarificationResolvedAt()).isNull();
        assertThat(dto.getLastPharmacyEvent()).isEqualTo("PENDING_CLARIFICATION");
        assertThat(dto.getLastPharmacyEventAt()).isEqualTo(askedAt);
    }
}
