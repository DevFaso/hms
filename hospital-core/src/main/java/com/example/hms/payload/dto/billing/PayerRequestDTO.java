package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PayerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Billing-money plan PR 6: a new payer. Checked by {@code PayerCatalogueService}, which names every refused field at
 * once (code, name, type).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerRequestDTO {

    @NotBlank(message = "{billing.payer.codeRequired}")
    @Size(max = 40, message = "{billing.payer.codeSize}")
    private String code;
    /** As the hospital names it, never translated (D-8). */
    @NotBlank(message = "{billing.payer.nameRequired}")
    @Size(max = 150, message = "{billing.payer.nameSize}")
    private String name;
    @NotNull(message = "{billing.payer.typeRequired}")
    private PayerType payerType;
}
