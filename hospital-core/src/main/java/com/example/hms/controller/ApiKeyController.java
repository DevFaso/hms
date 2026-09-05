package com.example.hms.controller;

import com.example.hms.payload.dto.platform.ApiKeyCreateDTO;
import com.example.hms.payload.dto.platform.ApiKeyIssuedDTO;
import com.example.hms.payload.dto.platform.ApiKeyResponseDTO;
import com.example.hms.service.apikey.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * API keys for third-party clients (Tier 2 item 45): issue, rotate,
 * revoke, list. Hospital-scoped; the raw key appears only in the
 * issue/rotate response and is never retrievable again.
 */
@RestController
@RequestMapping(value = "/api-keys", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_IT_STAFF')")
@Tag(name = "API Keys", description = "Third-party client credentials (Tier 2 item 45)")
@SecurityRequirement(name = "bearerAuth")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @Operation(summary = "List this hospital's API keys",
        description = "Prefixes and lifecycle only - the raw key is never retrievable.")
    @GetMapping
    public ResponseEntity<List<ApiKeyResponseDTO>> list() {
        return ResponseEntity.ok(apiKeyService.list());
    }

    @Operation(summary = "Issue an API key",
        description = "The response carries the raw key ONCE; only its hash is stored.")
    @PostMapping
    public ResponseEntity<ApiKeyIssuedDTO> issue(@Valid @RequestBody ApiKeyCreateDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(apiKeyService.issue(request));
    }

    @Operation(summary = "Rotate an API key",
        description = "Revokes the key and issues a fresh one under the same label.")
    @PostMapping("/{keyId}/rotate")
    public ResponseEntity<ApiKeyIssuedDTO> rotate(@PathVariable UUID keyId) {
        return ResponseEntity.ok(apiKeyService.rotate(keyId));
    }

    @Operation(summary = "Revoke an API key",
        description = "Immediate and permanent - a revoked key never verifies again.")
    @PutMapping("/{keyId}/revoke")
    public ResponseEntity<ApiKeyResponseDTO> revoke(@PathVariable UUID keyId) {
        return ResponseEntity.ok(apiKeyService.revoke(keyId));
    }
}
