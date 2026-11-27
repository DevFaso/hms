package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.ImagingDuplicateOverrideReason;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Provisional rule 2 (2026-11-15): the overridden duplicate warning on the audit trail. */
class ImagingDuplicateOverrideTest {

    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final ImagingDuplicateOverride override = new ImagingDuplicateOverride(audit);

    @Test
    void anOverriddenPlacementIsAuditedWithItsReasonCodeAndNeverItsNote() {
        ImagingOrder earlier = order();
        ImagingOrder placed = order();
        placed.setDuplicateOfRecentOrder(true);
        placed.setDuplicateOverride(true);
        placed.setDuplicateOverrideReason(ImagingDuplicateOverrideReason.OTHER);
        placed.setDuplicateOverrideNote("Antécédent de cancer du poumon");
        placed.setDuplicateOverrideByUserId(UUID.randomUUID());

        override.audit(placed, List.of(earlier));

        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AuditEventType.IMAGING_ORDER_DUPLICATE_PLACED);
        assertThat(event.getValue().getEntityType()).isEqualTo("IMAGING_ORDER");
        assertThat(event.getValue().getResourceId()).isEqualTo(placed.getId().toString());
        assertThat(event.getValue().getUserId()).isEqualTo(placed.getDuplicateOverrideByUserId());
        assertThat(event.getValue().getEventDescription())
            .isEqualTo("Duplicate warning overridden (OTHER): imaging order " + placed.getId()
                + " placed while recent order " + earlier.getId() + " of the same study exists")
            .doesNotContain("cancer");
    }

    @Test
    void anOtherNoteShorterThanTenCharactersIsRefused() {
        // QA r81 D4108 class: « ok » is not a written explanation.
        for (String note : List.of("ok", "   ok      ", "123456789")) {
            com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO request = new com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO();
            request.setDuplicateOverride(true);
            request.setDuplicateOverrideReason(ImagingDuplicateOverrideReason.OTHER);
            request.setDuplicateOverrideNote(note);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ImagingDuplicateOverride.requireReason(request))
                .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                    e -> assertThat(e.getMessageKey()).isEqualTo("clinical.override.reasonMin"));
        }
        com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO request = new com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO();
        request.setDuplicateOverride(true);
        request.setDuplicateOverrideReason(ImagingDuplicateOverrideReason.OTHER);
        request.setDuplicateOverrideNote("Suivi oncologique");
        ImagingDuplicateOverride.requireReason(request);
    }

    @Test
    void anUnflaggedPlacementIsNotAudited() {
        override.audit(order(), List.of());
        verify(audit, never()).logEvent(any());
    }

    private static ImagingOrder order() {
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        order.setPatient(patient);
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        order.setHospital(hospital);
        return order;
    }
}
