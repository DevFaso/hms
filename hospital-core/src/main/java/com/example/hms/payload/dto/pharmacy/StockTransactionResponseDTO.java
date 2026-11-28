package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockTransactionResponseDTO {

    private UUID id;
    private UUID inventoryItemId;
    private UUID stockLotId;
    private String transactionType;
    private BigDecimal quantity;
    private String reason;
    private UUID referenceId;
    private UUID performedBy;
    /** QA D158: who did it, as a name the ledger can show. */
    private String performedByName;
    private UUID witnessedBy;
    private String witnessedByName;
    /** QA D158: the lot's number, so the ledger never shows its id. */
    private String stockLotNumber;
    private LocalDateTime createdAt;

    /*
     * QA D4184 (r81): a movement names what moved. The list showed a type, a quantity and a lot number, and lot
     * numbers repeat across medicines, so « Transfert −20 R81-A-1 » could not be attributed.
     */
    /** The product in the reader's language, with its strength. */
    private String medicationName;
    /** The product's strength as written ("500 mg"), when the catalogue states one. */
    private String strength;
    /** The product's dosage form as the catalogue records it. */
    private String form;
    /** The unit the movement was recorded in; null on movements older than the unit snapshot (V398). */
    private String unit;
    /** A transfer's other store: where the stock went (TRANSFER) or where it came from (the paired RECEIPT). */
    private String counterpartPharmacyName;

    // ── FU-2 (P-06 backend): first-class RECEIPT fields ──
    private String lotNumber;
    private String supplier;
    private String poReference;
    private LocalDate expiryDate;
    private BigDecimal unitCost;
    private LocalDate receiptDate;
}
