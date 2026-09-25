package com.example.hms.mapper.pharmacy;

import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrescriptionRoutingMapperTest {

    private final PrescriptionRoutingMapper mapper = new PrescriptionRoutingMapper();

    private static PrescriptionRoutingDecision withReason(String reason) {
        return PrescriptionRoutingDecision.builder()
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.CANCELLED)
                .reason(reason)
                .build();
    }

    @Test
    @DisplayName("a no-show travels as a flag plus the pharmacist's words, never as a stored sentence")
    void splitsTheNoShowFactFromTheFreeText() {
        RoutingDecisionResponseDTO dto = mapper.toResponseDTO(
                withReason("Nearest partner has stock | [PARTNER_NO_SHOW] nobody at the counter"));

        assertThat(dto.isPartnerNoShow()).isTrue();
        assertThat(dto.getNoShowReason()).isEqualTo("nobody at the counter");
        assertThat(dto.getReason()).isEqualTo("Nearest partner has stock");
    }

    @Test
    @DisplayName("a row written before the marker decodes identically — nothing has to be rewritten")
    void decodesLegacyRows() {
        RoutingDecisionResponseDTO dto = mapper.toResponseDTO(
                withReason("Partner no-show: nobody at the counter"));

        assertThat(dto.isPartnerNoShow()).isTrue();
        assertThat(dto.getNoShowReason()).isEqualTo("nobody at the counter");
        assertThat(dto.getReason()).isNull();
    }

    @Test
    @DisplayName("an ordinary routing reason is passed through untouched")
    void leavesOrdinaryReasonsAlone() {
        RoutingDecisionResponseDTO dto = mapper.toResponseDTO(withReason("Medication out of stock"));

        assertThat(dto.isPartnerNoShow()).isFalse();
        assertThat(dto.getNoShowReason()).isNull();
        assertThat(dto.getReason()).isEqualTo("Medication out of stock");
    }

    @Test
    @DisplayName("a PENDING decision is never a no-show, whatever its reason says")
    void requiresTheStatusToCorroborate() {
        // A row already in the table whose authored reason happens to begin
        // with the legacy phrase. defuseAuthoredReason only protects rows
        // written from now on, so the status is the other half of the answer.
        PrescriptionRoutingDecision pending = PrescriptionRoutingDecision.builder()
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.PENDING)
                .reason("Partner no-show: last time, so routing elsewhere")
                .build();

        RoutingDecisionResponseDTO dto = mapper.toResponseDTO(pending);

        assertThat(dto.isPartnerNoShow()).isFalse();
        assertThat(dto.getNoShowReason()).isNull();
        // And the reason is handed over untouched, not stripped.
        assertThat(dto.getReason()).isEqualTo("Partner no-show: last time, so routing elsewhere");
    }

    @Test
    @DisplayName("a CANCELLED back order is not a partner no-show either")
    void requiresThePartnerRoutingType() {
        PrescriptionRoutingDecision backOrder = PrescriptionRoutingDecision.builder()
                .routingType(RoutingType.BACKORDER)
                .status(RoutingDecisionStatus.CANCELLED)
                .reason("Partner no-show: noted on the supplier call")
                .build();

        assertThat(mapper.toResponseDTO(backOrder).isPartnerNoShow()).isFalse();
    }

    @Test
    @DisplayName("a null entity maps to null")
    void nullEntity() {
        assertThat(mapper.toResponseDTO(null)).isNull();
    }
}
