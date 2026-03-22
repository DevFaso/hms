package com.example.hms.controller;

import com.example.hms.payload.dto.globalsetting.GlobalSettingRequestDTO;
import com.example.hms.payload.dto.globalsetting.GlobalSettingResponseDTO;
import com.example.hms.service.GlobalSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/super-admin/settings")
@RequiredArgsConstructor
@Tag(name = "Super Admin: Global Settings", description = "Manage platform-wide configuration settings")
public class GlobalSettingController {

    private final GlobalSettingService globalSettingService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "List all global settings")
    public ResponseEntity<List<GlobalSettingResponseDTO>> list(
        @RequestParam(name = "category", required = false) String category
    ) {
        List<GlobalSettingResponseDTO> result = category != null && !category.isBlank()
            ? globalSettingService.listByCategory(category)
            : globalSettingService.listAll();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(result);
    }

    @GetMapping("/{settingKey}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get a setting by key")
    public ResponseEntity<GlobalSettingResponseDTO> getByKey(@PathVariable String settingKey) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(globalSettingService.getByKey(settingKey));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create or update a global setting")
    public ResponseEntity<GlobalSettingResponseDTO> upsert(
        @Valid @RequestBody GlobalSettingRequestDTO request,
        Authentication authentication
    ) {
        String updatedBy = authentication != null ? authentication.getName() : "system";
        GlobalSettingResponseDTO result = globalSettingService.upsert(
            request.settingKey(),
            request.settingValue(),
            request.category(),
            request.description(),
            updatedBy
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(result);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Delete a global setting")
    public ResponseEntity<Void> delete(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        String deletedBy = authentication != null ? authentication.getName() : "system";
        globalSettingService.delete(id, deletedBy);
        return ResponseEntity.noContent().build();
    }
}
