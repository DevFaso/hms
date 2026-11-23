package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.service.notification.PatientRecipientChallengeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** Staff use the contact already on this hospital's patient record, never an arbitrary recipient. */
@RestController @RequiredArgsConstructor
@RequestMapping("/patients/{patientId}/recipient-verification")
@PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN')")
public class PatientRecipientVerificationController {
    private final PatientRecipientChallengeService service;
    private final PatientHospitalRegistrationRepository registrations;
    private final ControllerAuthUtils authUtils;
    public record Confirmation(@jakarta.validation.constraints.NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}

    @GetMapping
    public List<PatientRecipientChallengeService.View> status(@PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId, Authentication auth) {
        authorize(patientId, hospitalId, auth); return service.status(patientId);
    }
    @PostMapping("/{channel}/request")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN')")
    public PatientRecipientChallengeService.View request(@PathVariable UUID patientId, @PathVariable NotificationChannel channel,
        @RequestParam(required = false) UUID hospitalId, Authentication auth) {
        UUID actor = authorize(patientId, hospitalId, auth);
        return service.request(patientId, channel, actor, LocaleContextHolder.getLocale());
    }
    @PostMapping("/{channel}/confirm")
    public PatientRecipientChallengeService.View confirm(@PathVariable UUID patientId, @PathVariable NotificationChannel channel,
        @RequestParam(required = false) UUID hospitalId, @Valid @RequestBody Confirmation body, Authentication auth) {
        return service.confirm(patientId, channel, authorize(patientId, hospitalId, auth), body.code());
    }
    private UUID authorize(UUID patientId, UUID hospitalId, Authentication auth) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        if (scope == null || !registrations.existsByPatientIdAndHospitalId(patientId, scope))
            throw new ResourceNotFoundException("patient.notFound", patientId);
        return authUtils.resolveUserId(auth).orElseThrow(() -> new ResourceNotFoundException("user.notFound", (Object) null));
    }
}
