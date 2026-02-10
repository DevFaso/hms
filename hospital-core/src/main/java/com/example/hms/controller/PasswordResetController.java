package com.example.hms.controller;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.PasswordResetConfirmDTO;
import com.example.hms.payload.dto.PasswordResetRequestDTO;
import com.example.hms.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequestMapping("/auth/password")
@RequiredArgsConstructor
@Slf4j
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @PostMapping("/request")
    public ResponseEntity<Void> requestReset(
        @Valid @RequestBody PasswordResetRequestDTO dto,
        Locale locale,
        HttpServletRequest request
    ) {
        return handleResetRequest(dto.getEmail(), locale, request);
    }

    @PostMapping("/request-reset")
    public ResponseEntity<Void> requestResetQuery(
        @RequestParam("email") @Email @NotBlank String email,
        Locale locale,
        HttpServletRequest request
    ) {
        return handleResetRequest(email, locale, request);
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmDTO dto) {
        // dto.getToken() is the RAW token from the link; service hashes and validates internally
        try {
            passwordResetService.confirmReset(dto.getToken(), dto.getNewPassword());
        } catch (ResourceNotFoundException | IllegalStateException e) {
            // Don’t reveal token validity details to avoid giving attackers useful signals.
            log.debug("Password reset confirm failed: {}", e.getMessage());
            // You can still return 204 to keep response uniform.
        }
        return ResponseEntity.noContent().build(); // 204
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> legacyConfirmReset(@Valid @RequestBody PasswordResetConfirmDTO dto) {
        return confirmReset(dto);
    }

    /** Extract client IP, honoring common proxy headers. */
    private static String clientIp(HttpServletRequest request) {
        String h = request.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) {
            // XFF may contain a list: client, proxy1, proxy2...
            int comma = h.indexOf(',');
            return comma > 0 ? h.substring(0, comma).trim() : h.trim();
        }
        h = request.getHeader("X-Real-IP");
        return (h != null && !h.isBlank()) ? h.trim() : request.getRemoteAddr();
    }

    private ResponseEntity<Void> handleResetRequest(String email, Locale locale, HttpServletRequest request) {
        String ip = clientIp(request);
        try {
            // uses the hardened service method that stores only the hash
            // and enforces one active token per user
            passwordResetService.requestReset(email, locale, ip);
        } catch (ResourceNotFoundException e) {
            // Deliberately do NOT leak whether the email exists
            log.debug("Password reset requested for non-existent email: {}", email);
        }
        return ResponseEntity.noContent().build(); // 204
    }
}
