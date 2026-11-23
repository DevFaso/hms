package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.service.PatientPortalService;
import com.example.hms.service.notification.PatientRecipientChallengeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController @RequiredArgsConstructor
@RequestMapping("/me/patient/recipient-verification")
@PreAuthorize("hasAuthority('ROLE_PATIENT')")
public class PatientSelfRecipientVerificationController {
    private final PatientRecipientChallengeService service;
    private final PatientPortalService portal;
    private final ControllerAuthUtils authUtils;
    @GetMapping
    public List<PatientRecipientChallengeService.View> status(Authentication auth) {
        return service.status(portal.resolvePatientId(auth));
    }
    @PostMapping("/{channel}/request")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public PatientRecipientChallengeService.View request(@PathVariable NotificationChannel channel, Authentication auth) {
        return service.request(portal.resolvePatientId(auth), channel, actor(auth), LocaleContextHolder.getLocale());
    }
    @PostMapping("/{channel}/confirm")
    public PatientRecipientChallengeService.View confirm(@PathVariable NotificationChannel channel,
        @Valid @RequestBody PatientRecipientVerificationController.Confirmation body, Authentication auth) {
        return service.confirm(portal.resolvePatientId(auth), channel, actor(auth), body.code());
    }
    private UUID actor(Authentication auth) {
        authUtils.requireAuth(auth);
        return authUtils.resolveUserId(auth).orElseThrow(() -> new ResourceNotFoundException("user.notFound", (Object) null));
    }
}
