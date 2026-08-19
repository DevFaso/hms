package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockCheckResultDTO {

    private String medicationName;
    private String pharmacyName;
    private UUID pharmacyId;
    private BigDecimal quantityOnHand;
    private boolean sufficient;
    private List<PartnerOptionDTO> partnerPharmacies;
}
