package com.example.hms.controller;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.payload.dto.billing.PriceListDeactivateRequestDTO;
import com.example.hms.payload.dto.billing.PriceListItemDTO;
import com.example.hms.payload.dto.billing.PriceListItemRequestDTO;
import com.example.hms.payload.dto.billing.PriceListImportReportDTO;
import com.example.hms.payload.dto.billing.PriceListItemUpdateRequestDTO;
import com.example.hms.payload.dto.billing.PriceListLinkOptionsDTO;
import com.example.hms.payload.dto.billing.PriceListPriceRequestDTO;
import com.example.hms.enums.PriceListImportResult;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.service.billing.PriceListCsv;
import com.example.hms.service.billing.PriceListImportService;
import com.example.hms.service.billing.PriceListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>PR 4: the grid from and to a spreadsheet. {@code POST /import} (multipart {@code file}) checks the file by default
 * ({@code dryRun=true}) and applies it all or nothing with {@code dryRun=false}; only the maintainers reach it.
 * {@code GET /export.csv} writes the acting hospital's grid in the same format, for whoever reads the list.
 */
@RestController
@RequestMapping("/billing/price-list")
@Tag(name = "Price list", description = "The hospital's tariff, with effective-dated price versions")
@RequiredArgsConstructor
public class PriceListController {

    static final String READERS = "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_RECEPTIONIST','ROLE_CLAIMS_REVIEWER')";
    static final String MAINTAINERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT')";

    private static final int UNPROCESSABLE = 422;

    private final PriceListService priceListService;
    private final PriceListImportService priceListImportService;

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

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @PreAuthorize(READERS)
    @Operation(summary = "The acting hospital's price list as a CSV file, in the import's format")
    public ResponseEntity<byte[]> exportCsv() {
        byte[] body = priceListImportService.export();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(priceListImportService.exportFileName(), StandardCharsets.UTF_8).build().toString())
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(body);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Check (dryRun=true, the default) or apply (dryRun=false) a price-list CSV file; "
        + "an apply with any refused row writes nothing (422)")
    public ResponseEntity<PriceListImportReportDTO> importCsv(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean replaceScheduled) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new FieldBusinessException(PriceListCsv.FIELD_FILE, "billing.priceList.import.fileEmpty");
        }
        if (file.getSize() > PriceListCsv.MAX_BYTES) {
            throw new FieldBusinessException(PriceListCsv.FIELD_FILE, "billing.priceList.import.fileTooLarge");
        }
        PriceListImportReportDTO report = priceListImportService.importFile(file.getBytes(),
            file.getOriginalFilename(), dryRun, replaceScheduled);
        // 422 when an apply refused a row and wrote nothing; the body is the same per-row report.
        return ResponseEntity.status(report.getResult() == PriceListImportResult.REFUSED
                ? HttpStatusCode.valueOf(UNPROCESSABLE) : HttpStatus.OK)
            .body(report);
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
