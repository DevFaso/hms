package com.example.hms.controller;

import com.example.hms.payload.dto.ServiceTranslationRequestDTO;
import com.example.hms.payload.dto.ServiceTranslationResponseDTO;
import com.example.hms.service.ServiceTranslationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

@RestController
@RequestMapping("/service-translations")
@RequiredArgsConstructor
@Tag(name = "Service Translation Management", description = "APIs for managing treatment translations with multi-language support (EN, FR, ES).")
public class ServiceTranslationController {

    private final ServiceTranslationService translationService;
    private final MessageSource messageSource;

    @Operation(summary = "Create Translation", description = "Create a translation entry for a treatment.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Translation created successfully."),
                    @ApiResponse(responseCode = "400", description = "Invalid input data."),
                    @ApiResponse(responseCode = "403", description = "Unauthorized to perform this operation.")
            }
    )
    @PostMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<ServiceTranslationResponseDTO> createTranslation(
            @Valid @RequestBody ServiceTranslationRequestDTO requestDTO,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        ServiceTranslationResponseDTO created = translationService.createTranslation(requestDTO, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Get Translation by ID", description = "Retrieve a specific treatment translation by its ID.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Translation found."),
                    @ApiResponse(responseCode = "404", description = "Translation not found.")
            }
    )
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF')")
    public ResponseEntity<ServiceTranslationResponseDTO> getTranslationById(
            @Parameter(description = "Translation ID") @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(translationService.getTranslationById(id, locale));
    }

    @Operation(summary = "Get All Translations", description = "Retrieve all treatment translations in the preferred language.",
            responses = @ApiResponse(responseCode = "200", description = "List of translations returned.")
    )
    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF')")
    public ResponseEntity<List<ServiceTranslationResponseDTO>> getAllTranslations(
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(translationService.getAllTranslations(locale));
    }

    @Operation(summary = "Update Translation", description = "Update an existing translation entry.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Translation updated successfully."),
                    @ApiResponse(responseCode = "404", description = "Translation not found.")
            }
    )
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<ServiceTranslationResponseDTO> updateTranslation(
            @Parameter(description = "Translation ID") @PathVariable UUID id,
            @Valid @RequestBody ServiceTranslationRequestDTO requestDTO,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(translationService.updateTranslation(id, requestDTO, locale));
    }

    @Operation(summary = "Delete Translation", description = "Delete a translation by its ID.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Translation deleted successfully."),
                    @ApiResponse(responseCode = "404", description = "Translation not found.")
            }
    )
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<String> deleteTranslation(
            @Parameter(description = "Translation ID") @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        translationService.deleteTranslation(id, locale);
        String message = messageSource.getMessage("translation.deleted", new Object[]{id}, locale);
        return ResponseEntity.ok(message);
    }
}
