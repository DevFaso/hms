package com.example.hms.controller;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.payload.dto.billing.PriceListDeactivateRequestDTO;
import com.example.hms.payload.dto.billing.PriceListItemDTO;
import com.example.hms.payload.dto.billing.PriceListItemRequestDTO;
import com.example.hms.payload.dto.billing.PriceListItemUpdateRequestDTO;
import com.example.hms.payload.dto.billing.PriceListLinkOptionsDTO;
import com.example.hms.payload.dto.billing.PriceListPriceRequestDTO;
import com.example.hms.service.billing.PriceListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Billing-money plan PR 2 (scorecard M1): the hospital's price list ("grille tarifaire") at {@code /billing/price-list}.
 *
 * <p>Reads: hospital admin, accountant, billing specialist, receptionist, claims reviewer, and the platform
 * super-admin (read-only). Writes: hospital admin and accountant only (owner decision D-1); the service then requires
 * that role on an assignment AT the acting hospital, so the super-admin's inherited authority alone never writes.
 *
 * <p>There is deliberately no endpoint that edits or deletes a price version: a price changes only by adding one.
 */
@RestController
@RequestMapping("/billing/price-list")
@Tag(name = "Price list", description = "The hospital's tariff, with effective-dated price versions")
@RequiredArgsConstructor
public class PriceListController {

    static final String READERS = "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_RECEPTIONIST','ROLE_CLAIMS_REVIEWER')";
    static final String MAINTAINERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT')";

    private final PriceListService priceListService;

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "List the price list items, with the price in force today and the next planned one")
    public List<PriceListItemDTO> list(@RequestParam(required = false) PriceListCategory category,
                                       @RequestParam(defaultValue = "false") boolean includeInactive) {
        return priceListService.list(category, includeInactive);
    }

    @GetMapping("/link-options")
    @PreAuthorize(READERS)
    @Operation(summary = "What a price list item of the acting hospital can be linked to")
    public PriceListLinkOptionsDTO linkOptions() {
        return priceListService.linkOptions();
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    @Operation(summary = "One price list item with its price history")
    public PriceListItemDTO get(@PathVariable UUID id) {
        return priceListService.get(id);
    }

    @PostMapping
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Create a price list item with its first price")
    public ResponseEntity<PriceListItemDTO> create(@Valid @RequestBody(required = false) PriceListItemRequestDTO request) {
        return new ResponseEntity<>(priceListService.create(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Edit the label, category or link of a price list item (never its code or prices)")
    public PriceListItemDTO update(@PathVariable UUID id,
                                   @Valid @RequestBody(required = false) PriceListItemUpdateRequestDTO request) {
        return priceListService.update(id, request);
    }

    @PostMapping("/{id}/prices")
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Add a price version from a date (today or later)")
    public ResponseEntity<PriceListItemDTO> addPrice(@PathVariable UUID id,
                                                     @Valid @RequestBody(required = false) PriceListPriceRequestDTO request) {
        return new ResponseEntity<>(priceListService.addPrice(id, request), HttpStatus.CREATED);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Deactivate a price list item with a reason; its history is kept")
    public PriceListItemDTO deactivate(@PathVariable UUID id,
                                       @Valid @RequestBody(required = false) PriceListDeactivateRequestDTO request) {
        return priceListService.deactivate(id, request);
    }
}
