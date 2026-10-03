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

    private static PrescriptionRoutingDecision cancelledPartner(String reason) {
        return PrescriptionRoutingDecision.builder()
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.CANCELLED)
                .reason(reason)
                .build();
    }

    @Test
    @DisplayName("a no-show travels as the flag plus the pharmacist's words, both from their own columns")
    void readsTheNoShowFromItsColumns() {
        PrescriptionRoutingDecision decision = cancelledPartner("Nearest partner has stock");
        decision.setPartnerNoShow(true);
        decision.setNoShowReason("nobody at the counter");

        RoutingDecisionResponseDTO dto = mapper.toResponseDTO(decision);

        assertThat(dto.isPartnerNoShow()).isTrue();
        assertThat(dto.getNoShowReason()).isEqualTo("nobody at the counter");
        assertThat(dto.getReason()).isEqualTo("Nearest partner has stock");
    }

    @Test
    @DisplayName("the reason column is prose: the legacy phrase and the old marker are shown as typed, flagged as nothing")
    void theReasonIsNeverReadAsTheFact() {
        // Since V167 only the column says a no-show happened. What somebody
        // typed into the reason, whatever it looks like, reaches the reader
        // as they typed it.
        RoutingDecisionResponseDTO legacy = mapper.toResponseDTO(
                cancelledPartner("Partner no-show: last time, so routing elsewhere"));
        RoutingDecisionResponseDTO marker = mapper.toResponseDTO(
                cancelledPartner("[PARTNER_NO_SHOW] typed by hand"));

        assertThat(legacy.isPartnerNoShow()).isFalse();
        assertThat(legacy.getNoShowReason()).isNull();
        assertThat(legacy.getReason()).isEqualTo("Partner no-show: last time, so routing elsewhere");
        assertThat(marker.isPartnerNoShow()).isFalse();
        assertThat(marker.getReason()).isEqualTo("[PARTNER_NO_SHOW] typed by hand");
    }

    @Test
    @DisplayName("no-show words are never handed out without the flag")
    void wordsTravelOnlyWithTheFlag() {
        PrescriptionRoutingDecision decision = cancelledPartner("Medication out of stock");
        decision.setNoShowReason("left over");

        RoutingDecisionResponseDTO dto = mapper.toResponseDTO(decision);

        assertThat(dto.isPartnerNoShow()).isFalse();
        assertThat(dto.getNoShowReason()).isNull();
        assertThat(dto.getReason()).isEqualTo("Medication out of stock");
    }

    @Test
    @DisplayName("a null entity maps to null")
    void nullEntity() {
        assertThat(mapper.toResponseDTO(null)).isNull();
    }
}
