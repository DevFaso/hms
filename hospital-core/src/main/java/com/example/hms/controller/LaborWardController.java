package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeResponseDTO;
import com.example.hms.service.LaborService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * QA D3868: the labour ward's list, the women in labour at the caller's hospital. The maternity Labour tab opened
 * empty and the midwife had to know an exact identifier to reach a woman who was delivering. Same role set as
 * {@link LaborController}'s reads; the hospital is the request's scope (required: there is no global list).
 */
@RestController
@RequestMapping("/labor")
@RequiredArgsConstructor
@Tag(name = "Labor & Delivery", description = "Labor episodes, WHO partograph capture, and delivery records")
public class LaborWardController {

    private final LaborService laborService;
    private final ControllerAuthUtils authUtils;

    @GetMapping("/episodes/active")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Every woman in labour at this hospital, newest admission first",
        description = "Not paged: the active labours of one hospital are its labour ward, a handful of rows.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<LaborEpisodeResponseDTO>> activeEpisodes(
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, true);
        return ResponseEntity.ok(laborService.getActiveEpisodesAt(resolvedHospitalId));
    }
}
