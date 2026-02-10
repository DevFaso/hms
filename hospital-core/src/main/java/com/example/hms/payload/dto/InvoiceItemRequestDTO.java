package com.example.hms.payload.dto;

import com.example.hms.enums.ItemCategory;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceItemRequestDTO {

    private UUID id;

    @NotNull
    private UUID billingInvoiceId;

    @NotBlank
    private String itemDescription;

    @NotNull
    @Min(1)
    private Integer quantity;

    @NotNull
    @Digits(integer = 10, fraction = 2)
    @Positive
    private BigDecimal unitPrice;

    @NotNull
    private ItemCategory itemCategory;

    @NotNull
    private UUID assignmentId;

    private UUID relatedServiceId;
}
