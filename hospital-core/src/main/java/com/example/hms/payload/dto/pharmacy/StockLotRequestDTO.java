package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockLotRequestDTO {

    @NotNull(message = "Inventory item ID is required")
    private UUID inventoryItemId;

    @NotBlank(message = "Lot number is required")
    @Size(max = 80)
    private String lotNumber;

    @NotNull(message = "Expiry date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expiryDate;

    @NotNull(message = "Initial quantity is required")
    private BigDecimal initialQuantity;

    private BigDecimal remainingQuantity;

    @Size(max = 255)
    private String supplier;

    private BigDecimal unitCost;

    @NotNull(message = "Received date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate receivedDate;

    private UUID receivedBy;

    @Size(max = 1000)
    private String notes;
}
