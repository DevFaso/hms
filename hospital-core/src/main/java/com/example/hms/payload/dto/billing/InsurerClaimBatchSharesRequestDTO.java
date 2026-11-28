package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * Insurer claims plan PR 2 (US-5): payer shares to add to a draft bordereau, and shares to take off it. A share goes
 * on a bordereau whole, with every act it claims.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerClaimBatchSharesRequestDTO {

    private List<UUID> add;

    private List<UUID> remove;
}
