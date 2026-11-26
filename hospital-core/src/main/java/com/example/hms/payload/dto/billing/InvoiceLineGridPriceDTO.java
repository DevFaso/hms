package com.example.hms.payload.dto.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Review of #1338 (QA D3980): the grid price a line typed on a draft would take for one price-list item: the version
 * in force on the invoice's service date. {@code unitPrice} is null when no version is in force that day (the line is
 * then priced by hand and still bills its act).
 */
public record InvoiceLineGridPriceDTO(UUID priceListItemId, LocalDate serviceDate, BigDecimal unitPrice) {
}
