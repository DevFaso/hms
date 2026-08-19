package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacyPaymentResponseDTO {

    private UUID id;
    private UUID dispenseId;
    private UUID patientId;
    private UUID hospitalId;
    private String paymentMethod;
    private BigDecimal amount;
    private String currency;
    private String referenceNumber;
    private UUID receivedBy;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
