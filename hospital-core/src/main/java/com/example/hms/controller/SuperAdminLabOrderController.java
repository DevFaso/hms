package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminLabOrderCreateRequestDTO;
import com.example.hms.service.SuperAdminLabOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/super-admin/lab-orders")
@RequiredArgsConstructor
@Tag(name = "Super Admin Lab Orders", description = "Order laboratory tests across organizations without exposing internal identifiers")
public class SuperAdminLabOrderController {

    private final SuperAdminLabOrderService superAdminLabOrderService;

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        summary = "Create lab order for a patient",
        description = "Allows super admins to create lab orders by referencing human-friendly organization, hospital, staff, and patient identifiers.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ApiResponseWrapper<LabOrderResponseDTO>> createLabOrder(
        @Valid @RequestBody SuperAdminLabOrderCreateRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        LabOrderResponseDTO response = superAdminLabOrderService.createLabOrder(request, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.success(response));
    }
}
