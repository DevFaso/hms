package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.clinicalrules.ClinicalRuleStatusDTO;
import com.example.hms.payload.dto.clinicalrules.ClinicalRulesOverviewDTO;
import com.example.hms.payload.dto.clinicalrules.RuleSignoffRequestDTO;
import com.example.hms.payload.dto.clinicalrules.RuleSignoffRevokeRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.clinicalrules.HospitalRuleSignoffService;
import com.example.hms.service.clinicalrules.RuleSignoffPackPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * V356 (owner direction 2026-11-21): « Règles cliniques et validation » — every provisional rule set, its source,
 * its approver role and where THIS hospital stands on it; the hospital admin records the approver's sign-off or
 * revokes it, and prints the French sign-off pack.
 *
 * <p>Tenancy: the path's hospital is the acting one ({@code HospitalIdNarrowingInterceptor} narrows to it or
 * refuses it); what is left is that the request really acts there — a hospital's staff and admin at their own
 * hospital, the super-admin only for the hospital they selected (the path). The clinical staff who see a
 * « provisoire » label read the list; only the hospital admin (or the super-admin) writes. Recording a sign-off
 * changes no rule's behaviour.
 */
@Tag(name = "Clinical rules", description = "Provisional rule sets and each hospital's sign-off")
@RestController
@RequiredArgsConstructor
@RequestMapping("/hospitals/{hospitalId}/clinical-rules")
public class HospitalClinicalRulesController {

    /** Who reads the list (and the labels built on it): the admins and the staff who see a « provisoire » rule. */
    public static final String READ_ROLES = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN',"
        + "'ROLE_DOCTOR','ROLE_SURGEON','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_RADIOLOGIST',"
        + "'ROLE_IMAGING_TECHNICIAN','ROLE_LAB_DIRECTOR','ROLE_QUALITY_MANAGER','ROLE_BILLING_SPECIALIST',"
        + "'ROLE_ACCOUNTANT')";
    /** Who records or revokes a sign-off; the service narrows the hospital admin to an assignment there. */
    public static final String WRITE_ROLES = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";
    private static final String PACK_FILE_NAME = "regles-cliniques-validation.pdf";

    private final HospitalRuleSignoffService signoffService;
    private final RuleSignoffPackPdfService packService;
    private final ControllerAuthUtils authUtils;

    @Operation(summary = "Every provisional rule set and its sign-off status at this hospital")
    @GetMapping
    @PreAuthorize(READ_ROLES)
    public ResponseEntity<ClinicalRulesOverviewDTO> list(@PathVariable UUID hospitalId, Authentication auth) {
        requireActingThere(hospitalId);
        return ResponseEntity.ok(signoffService.overview(hospitalId, userId(auth)));
    }

    @Operation(summary = "Record the approver's sign-off of one rule set at its current version")
    @WriteAudited(skip = true, reason = "service emits CLINICAL_RULE_SIGNED_OFF after commit (ids only)")
    @PostMapping("/{ruleKey}/signoffs")
    @PreAuthorize(WRITE_ROLES)
    public ResponseEntity<ClinicalRuleStatusDTO> sign(@PathVariable UUID hospitalId, @PathVariable String ruleKey,
                                                      @Valid @RequestBody RuleSignoffRequestDTO body,
                                                      Authentication auth) {
        requireActingThere(hospitalId);
        return ResponseEntity.ok(signoffService.sign(hospitalId, ruleKey, body, userId(auth)));
    }

    @Operation(summary = "Revoke the sign-off of one rule set; it is provisional again")
    @WriteAudited(skip = true, reason = "service emits CLINICAL_RULE_SIGNOFF_REVOKED after commit (ids only)")
    @PostMapping("/{ruleKey}/revocation")
    @PreAuthorize(WRITE_ROLES)
    public ResponseEntity<ClinicalRuleStatusDTO> revoke(@PathVariable UUID hospitalId, @PathVariable String ruleKey,
                                                        @Valid @RequestBody RuleSignoffRevokeRequestDTO body,
                                                        Authentication auth) {
        requireActingThere(hospitalId);
        return ResponseEntity.ok(signoffService.revoke(hospitalId, ruleKey, body.reason(), userId(auth)));
    }

    @Operation(summary = "The French sign-off pack of this hospital (PDF, to sign on paper)")
    @GetMapping(value = "/signoff-pack", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(READ_ROLES)
    public ResponseEntity<byte[]> pack(@PathVariable UUID hospitalId, Authentication auth) {
        requireActingThere(hospitalId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(PACK_FILE_NAME).build().toString())
            .body(packService.render(hospitalId, userId(auth)));
    }

    private UUID userId(Authentication auth) {
        return authUtils.resolveUserId(auth).orElse(null);
    }

    /**
     * As the onboarding checklist: the request acts at the path's hospital (pinned there), or is the verified
     * super-admin's, who names the hospital in the path. A super-admin with no hospital never reaches here: the
     * path has one.
     */
    private static void requireActingThere(UUID hospitalId) {
        ActingScope scope = ActingScopeResolver.currentScope();
        boolean actingThere = scope instanceof ActingScope.Global
            || (scope instanceof ActingScope.Pinned pinned && pinned.hospitalId().equals(hospitalId));
        if (!actingThere) {
            throw new AccessDeniedException("Clinical rules are read and signed only at the hospital one acts at.");
        }
    }
}
