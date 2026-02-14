package com.example.hms.controller;

import com.example.hms.payload.dto.reference.CatalogImportResponseDTO;
import com.example.hms.payload.dto.reference.CreateReferenceCatalogRequestDTO;
import com.example.hms.payload.dto.reference.ReferenceCatalogResponseDTO;
import com.example.hms.payload.dto.reference.SchedulePublishRequestDTO;
import com.example.hms.service.ReferenceCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/reference/catalogs")
@Validated
@RequiredArgsConstructor
@Tag(name = "Reference Data", description = "Manage reference catalogs and lookup enumerations")
public class ReferenceCatalogController {

    private final ReferenceCatalogService catalogService;

    @GetMapping
    @Operation(summary = "List reference catalogs")
    @ApiResponse(responseCode = "200", description = "Catalogs retrieved successfully",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ReferenceCatalogResponseDTO.class)))
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<List<ReferenceCatalogResponseDTO>> listCatalogs() {
        return ResponseEntity.ok(catalogService.listCatalogs());
    }

    @PostMapping
    @Operation(summary = "Create a reference catalog")
    @ApiResponse(responseCode = "200", description = "Catalog created successfully",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ReferenceCatalogResponseDTO.class)))
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ReferenceCatalogResponseDTO> createCatalog(
        @Valid @RequestBody CreateReferenceCatalogRequestDTO requestDTO) {
        return ResponseEntity.ok(catalogService.createCatalog(requestDTO));
    }

    @PostMapping(value = "/{catalogId}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import catalog entries from CSV",
        description = "Imports entries. CSV must include a 'code' column; optional columns: label, description, metadata (JSON), active")
    @ApiResponse(responseCode = "200", description = "Catalog entries imported",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = CatalogImportResponseDTO.class)))
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<CatalogImportResponseDTO> importCatalog(
        @Parameter(description = "Catalog identifier") @PathVariable UUID catalogId,
        @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(catalogService.importCatalog(catalogId, file));
    }

    @PostMapping("/{catalogId}/schedule")
    @Operation(summary = "Schedule or trigger catalog publish")
    @ApiResponse(responseCode = "200", description = "Publish scheduled",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ReferenceCatalogResponseDTO.class)))
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ReferenceCatalogResponseDTO> schedulePublish(
        @Parameter(description = "Catalog identifier") @PathVariable UUID catalogId,
        @Valid @RequestBody SchedulePublishRequestDTO requestDTO) {
        return ResponseEntity.ok(catalogService.schedulePublish(catalogId, requestDTO));
    }
}
