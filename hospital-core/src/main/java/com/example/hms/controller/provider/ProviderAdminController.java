package com.example.hms.controller.provider;

import com.example.hms.payload.dto.provider.ProviderAuditPageDTO;
import com.example.hms.payload.dto.provider.ProviderProfileDTO;
import com.example.hms.payload.dto.provider.ProviderProfileUpdateDTO;
import com.example.hms.payload.dto.provider.ProviderSettingsDTO;
import com.example.hms.payload.dto.provider.ProviderStaffMemberDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.security.provider.ProviderConfinementPolicy;
import com.example.hms.service.provider.ProviderAdminService;
import com.example.hms.service.provider.ProviderAuditTrailService;
import com.example.hms.utility.ActivationDeliveryTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * A provider facility (a private pharmacy or laboratory) administering itself
 * (provider plan US-2, AC-6, §6.5): its profile, its staff, its own audit
 * trail (§3.1), and the settings its portal shell reads. Registering a new
 * staff member stays {@code POST /users/admin-register} (the provider
 * registrar path).
 *
 * <p>Who may call is decided from the caller's LIVE assignments at the
 * facility the request acts at ({@code ProviderSeatResolver}), never from
 * {@code hasAuthority} alone (§3.3), so the annotation admits any
 * authenticated caller and names no role. Everyone the service turns away (a
 * hospital user, a super-admin, a provider user who is not the facility's
 * PROVIDER_ADMIN where that is required, a staff member who is unknown,
 * works elsewhere, is a peer administrator or is the caller) gets exactly the
 * answer of an unmapped path: a 403 would tell them the endpoint exists. The
 * seat is checked before the body is even parsed (it arrives as a raw
 * string; Jackson runs after the seat check) and before the member id is
 * parsed, so neither a 400 nor a malformed id tells them either. (A wrong
 * method or media type is still answered by MVC itself, before any handler.)
 *
 * <p>Not gated by {@code provider.organisations.enabled}: the flag gates the
 * directory and the pickers, never onboarding (plan §6.11). A facility's
 * staff log in to its shell and its admin manages them before the flag is on
 * (rollout step 1), and deactivating a member is a security control (T4).
 */
@RestController
@RequestMapping("/provider")
@RequiredArgsConstructor
@Tag(name = "Provider facility — administration",
    description = "A private pharmacy's or laboratory's own profile, staff and shell settings.")
public class ProviderAdminController {

    private static final String AUTHENTICATED = "isAuthenticated()";

    private final ProviderAdminService providerAdminService;
    private final ProviderAuditTrailService providerAuditTrailService;

    @GetMapping("/profile")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "The caller's provider facility: operational contact and verified identity",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderProfileDTO> getProfile(HttpServletRequest request) throws NoResourceFoundException {
        return ResponseEntity.ok(providerAdminService.getProfile()
            .orElseThrow(() -> ProviderConfinementPolicy.unmapped(request)));
    }

    @PutMapping("/profile")
    @PreAuthorize(AUTHENTICATED)
    @WriteAudited(entity = "PROVIDER_FACILITY")
    @Operation(summary = "PROVIDER_ADMIN: change the facility's operational contact (phone, email, website)",
        // The body is bound as a raw string (parsed after the seat check); the
        // schema it must follow is documented here. Fully qualified: Spring's
        // @RequestBody has the same simple name.
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ProviderProfileUpdateDTO.class))),
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderProfileDTO> updateProfile(@RequestBody(required = false) String body,
                                                            HttpServletRequest request) throws NoResourceFoundException {
        return ResponseEntity.ok(providerAdminService.updateProfile(body)
            .orElseThrow(() -> ProviderConfinementPolicy.unmapped(request)));
    }

    @GetMapping("/staff")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "PROVIDER_ADMIN: the facility's staff", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<ProviderStaffMemberDTO>> listStaff(HttpServletRequest request)
            throws NoResourceFoundException {
        return ResponseEntity.ok(providerAdminService.listStaff()
            .orElseThrow(() -> ProviderConfinementPolicy.unmapped(request)));
    }

    @PostMapping("/staff/{userId}/deactivate")
    @PreAuthorize(AUTHENTICATED)
    @WriteAudited(entity = "PROVIDER_STAFF", idVar = "userId")
    @Operation(summary = "PROVIDER_ADMIN: take a staff member's assignments here out of use",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderStaffMemberDTO> deactivateStaff(@PathVariable String userId,
                                                                  HttpServletRequest request)
            throws NoResourceFoundException {
        return ResponseEntity.ok(providerAdminService.deactivateStaff(userId)
            .orElseThrow(() -> ProviderConfinementPolicy.unmapped(request)));
    }

    @PostMapping("/staff/{userId}/activate")
    @PreAuthorize(AUTHENTICATED)
    @WriteAudited(entity = "PROVIDER_STAFF", idVar = "userId")
    @Operation(summary = "PROVIDER_ADMIN: send a staff member a new invitation code for their inactive assignments here",
        description = "The assignment comes back on when its holder enters the code; the response reports "
            + "where the code was sent.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderStaffMemberDTO> activateStaff(@PathVariable String userId,
                                                                HttpServletRequest request)
            throws NoResourceFoundException {
        // The invitation is sent AFTER_COMMIT on this thread; drain its
        // outcomes so the admin sees whether the code went anywhere.
        ActivationDeliveryTracker.open();
        try {
            ProviderStaffMemberDTO member = providerAdminService.activateStaff(userId)
                .orElseThrow(() -> ProviderConfinementPolicy.unmapped(request));
            member.setActivationDelivery(ActivationDeliveryTracker.close());
            return ResponseEntity.ok(member);
        } finally {
            ActivationDeliveryTracker.close();
        }
    }

    @GetMapping("/audit")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "PROVIDER_ADMIN: the facility's own audit trail, newest first (ids and codes, no patient rows)",
        description = "page is zero-based; size defaults to 20 and is capped at 100. Both are read only once "
            + "the caller is known to be the facility's admin.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderAuditPageDTO> auditTrail(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            HttpServletRequest request) throws NoResourceFoundException {
        // Raw strings: a malformed value binds anyway, and is parsed after the seat check.
        return ResponseEntity.ok(providerAuditTrailService.trail(page, size)
            .orElseThrow(() -> ProviderConfinementPolicy.unmapped(request)));
    }

    @GetMapping("/settings")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "What the provider portal shell needs: the facility type and the provider flags",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderSettingsDTO> getSettings(HttpServletRequest request)
            throws NoResourceFoundException {
        return ResponseEntity.ok(providerAdminService.getSettings()
            .orElseThrow(() -> ProviderConfinementPolicy.unmapped(request)));
    }
}
