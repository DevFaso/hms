package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SaleLineRequestDTO {

    @NotNull(message = "{saleLine.medicationCatalogItemId.required}")
    private UUID medicationCatalogItemId;

    /** Optional: links the line to a specific stock lot for traceability. */
    private UUID stockLotId;

    @NotNull(message = "{saleLine.quantity.required}")
    @DecimalMin(value = "0.001", message = "{saleLine.quantity.positive}")
    private BigDecimal quantity;

    @NotNull(message = "{saleLine.unitPrice.required}")
    @DecimalMin(value = "0.0", message = "{saleLine.unitPrice.min}")
    private BigDecimal unitPrice;

    @Size(max = 500)
    private String notes;
}
