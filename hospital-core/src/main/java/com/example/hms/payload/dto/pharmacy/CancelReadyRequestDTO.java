package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.ReadyCancelReason;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * G15: {@code POST /pharmacy/dispense/{id}/cancel-ready}. The reason is one of
 * STOCK_UNAVAILABLE, PATIENT_DECLINED, NOT_COLLECTED or OTHER; the
 * PRESCRIPTION_* values are the system's own and are refused.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancelReadyRequestDTO {

    @NotNull(message = "{dispense.ready.cancelReason.required}")
    private ReadyCancelReason reason;
}
