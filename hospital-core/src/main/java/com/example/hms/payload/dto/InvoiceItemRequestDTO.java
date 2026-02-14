package com.example.hms.payload.dto;

import com.example.hms.enums.ItemCategory;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
