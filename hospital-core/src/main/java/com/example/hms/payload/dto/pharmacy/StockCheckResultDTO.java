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
    /**
     * QA D913: what the order needs, so the page can tell "50 on hand of 60
     * needed" apart from a stock-out instead of calling both "out of stock".
     */
    private BigDecimal quantityRequired;
    private boolean sufficient;
    /**
     * QA D966: the order's status, and whether a routing action (partner,
     * print, back order) can still be taken on it. After a print the buttons
     * stayed live and then failed; the page now offers the printed copy.
     */
    private String prescriptionStatus;
    private boolean routable;
    private List<PartnerOptionDTO> partnerPharmacies;
    /**
     * QA D4193 (r81): what each of the hospital's own stores holds. {@link #quantityOnHand} is their total: 118 was
     * shown for an order the dispensing pharmacy could fill with 83, the rest sitting in a ward reserve.
     */
    private List<StoreStock> stores;

    /** One of the hospital's own stores and what it holds of the prescribed medicine. */
    public record StoreStock(UUID pharmacyId, String pharmacyName, BigDecimal quantityOnHand, String unit) { }
}
