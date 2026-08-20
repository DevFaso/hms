package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.service.PhoneVerificationService;
import com.example.hms.utility.RoleValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * SMS verification of a patient's phone number at the registration desk.
 * Most patients have no email address, so a verified phone is the identity
 * anchor for cross-hospital matching and credential delivery. Codes are
 * generated and checked by IKODDI; requests are attributed to the staff
 * member who initiated them.
 */
@RestController
@RequestMapping(value = "/patients/phone-verification", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class PhoneVerificationController {

    private final PhoneVerificationService phoneVerificationService;
    private final ControllerAuthUtils authUtils;
    private final RoleValidator roleValidator;

    public record RequestVerificationBody(@NotBlank String phoneNumber) {}

    public record ConfirmBody(@NotNull UUID challengeId, @NotBlank String code) {}

    public record ChallengeResponse(UUID challengeId, String maskedPhone, LocalDateTime expiresAt, boolean verified) {}

    @Operation(summary = "Is SMS phone verification available (IKODDI configured)?",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/availability")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_NURSE','ROLE_MIDWIFE','ROLE_SUPER_ADMIN')")
    public ResponseEntity<Map<String, Boolean>> availability() {
        return ResponseEntity.ok(Map.of("available", phoneVerificationService.isAvailable()));
    }

    @Operation(summary = "Send a verification code to the patient's phone",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_NURSE','ROLE_MIDWIFE','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ChallengeResponse> requestVerification(
        @Valid @RequestBody RequestVerificationBody body, Authentication auth) {
        UUID userId = requireUserId(auth);
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        PhoneVerificationService.ChallengeView view =
            phoneVerificationService.requestRegistrationVerification(body.phoneNumber(), userId, hospitalId);
        return ResponseEntity.ok(toResponse(view));
    }

    @Operation(summary = "Confirm the code the patient read back",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_NURSE','ROLE_MIDWIFE','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ChallengeResponse> confirm(@Valid @RequestBody ConfirmBody body, Authentication auth) {
        UUID userId = requireUserId(auth);
        PhoneVerificationService.ChallengeView view =
            phoneVerificationService.confirmRegistrationVerification(body.challengeId(), body.code(), userId);
        return ResponseEntity.ok(toResponse(view));
    }

    private UUID requireUserId(Authentication auth) {
        authUtils.requireAuth(auth);
        return authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("Unable to resolve the requesting user."));
    }

    private static ChallengeResponse toResponse(PhoneVerificationService.ChallengeView view) {
        return new ChallengeResponse(view.challengeId(), view.maskedPhone(), view.expiresAt(), view.verified());
    }
}
