package com.example.hms.payload.dto.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Insurer claims plan PR 3 (US-11): money of a recorded payment, left to allocate, put on bordereau lines.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerPaymentAllocationsRequestDTO {

    @NotEmpty(message = "{billing.insurerPayment.allocationsRequired}")
    @Valid
    private List<InsurerPaymentAllocationRequestDTO> allocations;
}
