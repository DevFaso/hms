package com.example.hms.controller;

import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.payload.dto.credential.MfaResetRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.MfaResetService;
import com.example.hms.utility.MessageUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * QA r64 (lost phone): an administrator resets a colleague's two-step verification. The account rule is
 * {@code UserAccountAccess#canAdminister} (a hospital admin: only accounts wholly at their hospitals that hold no
 * admin role, so never a super-admin's); the reason is mandatory; the administrator's own step-up goes in
 * {@code X-Mfa-Token} when they have an authenticator.
 */
@RestController
@RequestMapping("/users/{userId}/mfa")
@RequiredArgsConstructor
@Tag(name = "User two-step verification", description = "Reset a colleague's two-step verification")
public class UserMfaResetController {

    private final MfaResetService mfaResetService;

    @PostMapping("/reset")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN')")
    @WriteAudited(skip = true, reason = "service emits MFA_RESET")
    @Operation(summary = "Reset a user's two-step verification (reason required; the actor's step-up in X-Mfa-Token)")
    public ResponseEntity<MessageResponse> reset(
        @PathVariable UUID userId,
        @Valid @RequestBody MfaResetRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String stepUpCode
    ) {
        mfaResetService.resetByAdministrator(userId, request.getReason(), stepUpCode);
        return ResponseEntity.ok(new MessageResponse(MessageUtil.resolve("mfa.reset.done")));
    }
}
