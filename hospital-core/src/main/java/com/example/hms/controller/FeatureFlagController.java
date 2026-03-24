package com.example.hms.controller;

import com.example.hms.payload.dto.featureflag.FeatureFlagOverrideRequestDTO;
import com.example.hms.service.FeatureFlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
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

@RestController
@RequestMapping("/feature-flags")
@RequiredArgsConstructor
@Tag(name = "Feature Flags", description = "Resolve effective feature flags with environment-aware overrides")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    @GetMapping
    @Operation(summary = "List effective feature flags")
    public ResponseEntity<Map<String, Boolean>> listFeatureFlags(
        @RequestParam(name = "env", required = false) String environment
    ) {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, Boolean> payload = featureFlagService.listFlags(environment, locale);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(payload);
    }

    @PutMapping("/{flagKey}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Override a feature flag")
    public ResponseEntity<Map<String, Boolean>> overrideFeatureFlag(
        @PathVariable("flagKey") String flagKey,
        @Valid @RequestBody FeatureFlagOverrideRequestDTO request,
        @RequestParam(name = "env", required = false) String environment,
        Authentication authentication
    ) {
        Locale locale = LocaleContextHolder.getLocale();
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        String description = request.description();
        String updatedBy = authentication != null ? authentication.getName() : "system";
        Map<String, Boolean> payload = featureFlagService.upsertOverride(
            flagKey,
            enabled,
            description,
            updatedBy,
            environment,
            locale
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(payload);
    }

    @DeleteMapping("/{flagKey}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Remove feature flag override")
    public ResponseEntity<Map<String, Boolean>> deleteFeatureFlagOverride(
        @PathVariable("flagKey") String flagKey,
        @RequestParam(name = "env", required = false) String environment,
        Authentication authentication
    ) {
        Locale locale = LocaleContextHolder.getLocale();
        String updatedBy = authentication != null ? authentication.getName() : "system";
        Map<String, Boolean> payload = featureFlagService.deleteOverride(
            flagKey,
            updatedBy,
            environment,
            locale
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(payload);
    }
}
