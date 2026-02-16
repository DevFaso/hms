package com.example.hms.controller;

import com.example.hms.payload.dto.dashboard.DashboardConfigResponseDTO;
import com.example.hms.service.DashboardConfigurationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard")
public class DashboardController {

    private final DashboardConfigurationService dashboardConfigurationService;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get dashboard configuration for the authenticated user")
    public ResponseEntity<DashboardConfigResponseDTO> getDashboardForCurrentUser() {
        DashboardConfigResponseDTO response = dashboardConfigurationService.getDashboardForCurrentUser();
        return ResponseEntity.ok(response);
    }
}
