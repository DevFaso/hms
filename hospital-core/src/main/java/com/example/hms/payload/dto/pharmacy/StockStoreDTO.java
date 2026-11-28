package com.example.hms.payload.dto.pharmacy;

import java.util.UUID;

/** QA D4183: one of the hospital's own stores (a dispensary that holds stock), for the stock-count screens. */
public record StockStoreDTO(UUID pharmacyId, String pharmacyName) { }
