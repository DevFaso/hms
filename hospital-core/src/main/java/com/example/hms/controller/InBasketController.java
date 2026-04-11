package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.clinical.InBasketItemDTO;
import com.example.hms.payload.dto.clinical.InBasketSummaryDTO;
import com.example.hms.service.InBasketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(
    value = "/in-basket",
    produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "In-Basket", description = "Provider In-Basket for lab/imaging results, orders, messages, and tasks")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class InBasketController {

    private final InBasketService inBasketService;
    private final ControllerAuthUtils authUtils;

    // ----------------------------------------------------------
    // List items (paged, filtered by type / status)
    // ----------------------------------------------------------
    @GetMapping(consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List In-Basket items", description = "Returns paginated In-Basket items for the current user, ordered by priority then date.")
    public ResponseEntity<Page<InBasketItemDTO>> list(
            @RequestParam(required = false) InBasketItemType type,
            @RequestParam(required = false) InBasketItemStatus status,
            @RequestParam(required = false) UUID hospitalId,
            @ParameterObject @PageableDefault(size = 20)
            Pageable pageable,
            Authentication auth) {

        UUID userId = requireUserId(auth);
        UUID resolvedHospitalId = resolveHospital(auth, hospitalId);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Hospital context is required. Pass hospitalId or ensure your token has a hospital scope.");
        }

        Page<InBasketItemDTO> page = inBasketService.getItems(userId, resolvedHospitalId, type, status, pageable);
        return ResponseEntity.ok(page);
    }

    // ----------------------------------------------------------
    // Unread count summary
    // ----------------------------------------------------------
    @GetMapping(value = "/summary", consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get In-Basket unread summary", description = "Returns unread counts grouped by item type.")
    public ResponseEntity<InBasketSummaryDTO> summary(
            @RequestParam(required = false) UUID hospitalId,
            Authentication auth) {

        UUID userId = requireUserId(auth);
        UUID resolvedHospitalId = resolveHospital(auth, hospitalId);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Hospital context is required. Pass hospitalId or ensure your token has a hospital scope.");
        }

        InBasketSummaryDTO summary = inBasketService.getSummary(userId, resolvedHospitalId);
        return ResponseEntity.ok(summary);
    }

    // ----------------------------------------------------------
    // Mark as read
    // ----------------------------------------------------------
    @PutMapping(value = "/{id}/read", consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Mark item as read")
    public ResponseEntity<InBasketItemDTO> markAsRead(
            @PathVariable UUID id,
            Authentication auth) {

        UUID userId = requireUserId(auth);
        InBasketItemDTO dto = inBasketService.markAsRead(id, userId);
        return ResponseEntity.ok(dto);
    }

    // ----------------------------------------------------------
    // Acknowledge
    // ----------------------------------------------------------
    @PutMapping(value = "/{id}/acknowledge", consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Acknowledge an In-Basket item")
    public ResponseEntity<InBasketItemDTO> acknowledge(
            @PathVariable UUID id,
            Authentication auth) {

        UUID userId = requireUserId(auth);
        InBasketItemDTO dto = inBasketService.acknowledge(id, userId);
        return ResponseEntity.ok(dto);
    }

    // ─── helpers ──────────────────────────────────────────────

    private UUID requireUserId(Authentication auth) {
        authUtils.requireAuth(auth);
        return authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException("Unable to resolve user from authentication token."));
    }

    private UUID resolveHospital(Authentication auth, UUID hospitalId) {
        return authUtils.resolveHospitalScope(auth, hospitalId, null, false);
    }
}
