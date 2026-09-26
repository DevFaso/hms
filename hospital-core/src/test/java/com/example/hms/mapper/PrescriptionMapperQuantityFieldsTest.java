package com.example.hms.mapper;

import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap G13 — the prescriber could be shown a remainder but not its unit,
 * because the response carried neither the ordered quantity nor the refill
 * counters. The portal's remainder is
 * {@code quantity * (1 + refillsUsed) - dispensed}, which is the arithmetic
 * {@code DispenseServiceImpl} runs, so all three fields have to arrive.
 */
@DisplayName("PrescriptionMapper: quantity and refill fields")
class PrescriptionMapperQuantityFieldsTest {

    private final PrescriptionMapper mapper = new PrescriptionMapper();

    private static Prescription base() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setMedicationName("Amoxicilline 500 mg");
        return p;
    }

    @Test
    @DisplayName("ordered quantity, its unit and all three refill counters are projected")
    void quantityAndRefillsAreProjected() {
        Prescription p = base();
        p.setQuantity(new BigDecimal("30.00"));
        p.setQuantityUnit("comprimés");
        p.setRefillsAllowed(2);
        p.setRefillsRemaining(1);
        p.setRefillsUsed(1);

        PrescriptionResponseDTO dto = mapper.toResponseDTO(p);

        assertThat(dto.getQuantity()).isEqualByComparingTo("30.00");
        assertThat(dto.getQuantityUnit()).isEqualTo("comprimés");
        assertThat(dto.getRefillsAllowed()).isEqualTo(2);
        assertThat(dto.getRefillsRemaining()).isEqualTo(1);
        assertThat(dto.getRefillsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("a row with no quantity on it reports null rather than zero")
    void absentQuantityStaysAbsent() {
        // The columns are nullable and pre-date the pharmacy module, so a
        // legacy row has none. Zero would read as "nothing is owed" on the
        // prescriber's panel, which is the opposite of "we do not know".
        PrescriptionResponseDTO dto = mapper.toResponseDTO(base());

        assertThat(dto.getQuantity()).isNull();
        assertThat(dto.getQuantityUnit()).isNull();
        assertThat(dto.getRefillsAllowed()).isNull();
        assertThat(dto.getRefillsRemaining()).isNull();
    }

    @Test
    @DisplayName("the clarification-stripped patient copy keeps the quantity")
    void patientCopyKeepsTheQuantity() {
        // What a patient was prescribed is theirs; only the pharmacist-to-
        // prescriber consultation comes off (G7).
        Prescription p = base();
        p.setQuantity(new BigDecimal("30.00"));
        p.setQuantityUnit("comprimés");
        p.setClarificationReason("Dose?");

        PrescriptionResponseDTO dto = mapper.toResponseDTO(p).withoutClarificationExchange();

        assertThat(dto.getClarificationReason()).isNull();
        assertThat(dto.getQuantity()).isEqualByComparingTo("30.00");
        assertThat(dto.getQuantityUnit()).isEqualTo("comprimés");
    }
}
