package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PriceListImportResult;
import com.example.hms.enums.PriceListImportRowOutcome;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Billing-money plan PR 4: the per-row report of a price-list CSV import, for the dry run ("Vérifier le fichier") and
 * for the apply. Each refusal names the file's line, its column and the reason in the reader's language.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceListImportReportDTO {

    /** The trace row (V304); null when nothing was recorded (a refused apply). */
    private UUID importId;
    private String fileName;
    private String sha256;
    private PriceListImportResult result;
    /** The same file was already applied at this hospital (on {@link #alreadyImportedAt}). */
    private boolean alreadyImported;
    private LocalDateTime alreadyImportedAt;
    private int totalRows;
    private int newItems;
    private int newPrices;
    private int unchanged;
    private int refused;
    private List<Row> rows;

    /** One data row of the file. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Row {
        /** The line in the file, the header being line 1. */
        private int line;
        private String code;
        private String label;
        private PriceListImportRowOutcome outcome;
        /** The row's price, in whole francs (null when it could not be read). */
        private BigDecimal price;
        /** The day the row's price applies from (today when the cell is blank). */
        private LocalDate effectiveDate;
        /** The price the row replaces from its date (NEW_PRICE), or keeps (UNCHANGED). */
        private BigDecimal previousPrice;
        private List<Refusal> refusals;
    }

    /** One reason a row is refused, with the CSV column it is about. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Refusal {
        private String column;
        private String message;
    }
}
