package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacySaleResponseDTO {

    private UUID id;
    private UUID pharmacyId;
    private String pharmacyName;
    private UUID hospitalId;
    private UUID patientId;
    private UUID soldByUserId;
    private LocalDateTime saleDate;
    private String paymentMethod;
    private BigDecimal totalAmount;
    private String currency;
    private String referenceNumber;
    private String status;
    private String notes;
    private List<SaleLineResponseDTO> lines;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
