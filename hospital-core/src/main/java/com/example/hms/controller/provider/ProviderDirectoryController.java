package com.example.hms.controller.provider;

import com.example.hms.config.SecurityConstants;
import com.example.hms.payload.dto.provider.ProviderDirectoryPageDTO;
import com.example.hms.service.provider.ProviderDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provider directory (provider plan §6.5, AC-14): the verified private
 * pharmacies and laboratories a hospital may add to its registry or route to.
 *
 * <p>Behind {@code provider.organisations.enabled} (default OFF), checked
 * first by the service every caller shares: with it off the directory is
 * empty for every caller the annotation admits, whatever they send (plan
 * AC-14, "the provider directory is empty").
 */
@RestController
@RequestMapping("/provider-directory")
@RequiredArgsConstructor
@Tag(name = "Provider directory", description = "Verified private pharmacies and laboratories on the platform.")
public class ProviderDirectoryController {

    private final ProviderDirectoryService directoryService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority(" + SecurityConstants.PROVIDER_DIRECTORY_AUTHORITIES + ")")
    @Operation(summary = "Verified, active provider facilities: at most 50, with hasMore"
        + " (empty while provider.organisations.enabled is off)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderDirectoryPageDTO> search(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "q", required = false) String query) {
        return ResponseEntity.ok(directoryService.search(type, query));
    }
}
