package com.example.hms.service.impl;

import com.example.hms.payload.dto.PrescriptionResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G7 — the patient-facing copy of a prescription keeps where to go and drops
 * the pharmacist-to-prescriber exchange.
 */
@DisplayName("PatientPortalServiceImpl: the patient's copy of a prescription")
class PatientPortalPrescriptionCopyTest {

    @Test
    void keepsThePharmacyAndDropsTheClarificationExchange() {
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder()
                .id(UUID.randomUUID())
                .pharmacyName("Pharmacie du Marché")
                .pharmacyContact("+22670000000")
                .dispatchStatus("SENT")
                .lastPharmacyEvent("PENDING_CLARIFICATION")
                .lastPharmacyEventAt(LocalDateTime.now())
                .clarificationReason("Dose au-dessus du plafond rénal")
                .clarificationRequestedAt(LocalDateTime.now())
                .clarificationResponse("Dose confirmée")
                .clarificationResolvedAt(LocalDateTime.now())
                .build();

        PrescriptionResponseDTO copy = dto.withoutClarificationExchange();

        assertThat(copy.getPharmacyName()).isEqualTo("Pharmacie du Marché");
        assertThat(copy.getPharmacyContact()).isEqualTo("+22670000000");
        assertThat(copy.getDispatchStatus()).isEqualTo("SENT");
        assertThat(copy.getClarificationReason()).isNull();
        assertThat(copy.getClarificationRequestedAt()).isNull();
        assertThat(copy.getClarificationResponse()).isNull();
        assertThat(copy.getClarificationResolvedAt()).isNull();
        // lastPharmacyEventAt IS the instant the pharmacist asked: stripping
        // the narrative and leaving the pair behind is the same leak.
        assertThat(copy.getLastPharmacyEvent()).isNull();
        assertThat(copy.getLastPharmacyEventAt()).isNull();
    }

    @Test
    void keepsAPharmacyEventThePatientShouldSee() {
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder()
                .id(UUID.randomUUID())
                .lastPharmacyEvent("DISPENSED")
                .lastPharmacyEventAt(LocalDateTime.now())
                .build();

        PrescriptionResponseDTO copy = dto.withoutClarificationExchange();

        assertThat(copy.getLastPharmacyEvent()).isEqualTo("DISPENSED");
        assertThat(copy.getLastPharmacyEventAt()).isNotNull();
    }

    @Test
    void returnsTheSameInstanceForChaining() {
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder().build();
        assertThat(dto.withoutClarificationExchange()).isSameAs(dto);
    }
}
