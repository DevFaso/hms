package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.recordaccess.SensitivityTagRequestDTO;
import com.example.hms.payload.dto.recordaccess.SensitivityTagResponseDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.recordaccess.SensitivityTaggingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * E8 #51 — set the sensitive-category tag on an encounter, or the default on a
 * department.
 *
 * <p>Both responses return the explicit tag and the <em>effective</em> one
 * separately, because they differ: clearing an encounter's tag does not make
 * it untagged if its department carries a default.
 *
 * <p>Nothing enforces the tag yet. Withholding happens when the read filter
 * widens (#49); until then a tag is recorded and returned, and changes no
 * read. That ordering is deliberate — the decision record requires the
 * withhold half to exist before the reach does.
 */
@Tag(name = "Record access", description = "Sensitive-category tagging (E8 #51)")
@RestController
@RequiredArgsConstructor
public class SensitivityTaggingController {

    /**
     * Who may classify a clinical row. Deliberately the clinicians who write
     * the record plus the admins who govern it — a receptionist can register a
     * patient but has no business deciding that a visit is behavioural health.
     */
    static final String TAG_ROLES = "hasAnyAuthority("
        + "'ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";
    static final String DEPARTMENT_ROLES = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final SensitivityTaggingService taggingService;
    private final ControllerAuthUtils authUtils;

    @Operation(summary = "Read an encounter's sensitivity tag",
        description = "Returns the explicit tag, the effective category after the department default, "
            + "and whether a row of that category may be read from another hospital.")
    @GetMapping("/encounters/{encounterId}/sensitivity")
    @PreAuthorize(TAG_ROLES)
    public ResponseEntity<SensitivityTagResponseDTO> getEncounterTag(@PathVariable UUID encounterId) {
        return ResponseEntity.ok(taggingService.getEncounterTag(encounterId));
    }

    @Operation(summary = "Tag an encounter with a sensitive category",
        description = "A null category clears the override, after which the department default applies.")
    @WriteAudited(skip = true, reason = "service emits DATA_UPDATE after commit with from/to categories")
    @PutMapping("/encounters/{encounterId}/sensitivity")
    @PreAuthorize(TAG_ROLES)
    public ResponseEntity<SensitivityTagResponseDTO> tagEncounter(
            @PathVariable UUID encounterId,
            @Valid @RequestBody SensitivityTagRequestDTO body,
            Authentication auth) {
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok(taggingService.tagEncounter(encounterId, body.category(), actorUserId));
    }

    @Operation(summary = "Read a department's default sensitive category")
    @GetMapping("/departments/{departmentId}/default-sensitivity")
    @PreAuthorize(DEPARTMENT_ROLES)
    public ResponseEntity<SensitivityTagResponseDTO> getDepartmentDefault(@PathVariable UUID departmentId) {
        return ResponseEntity.ok(taggingService.getDepartmentDefault(departmentId));
    }

    @Operation(summary = "Set a department's default sensitive category",
        description = "Applies to every untagged row recorded in the department, past and future. "
            + "A psychiatry department tags its encounters without anyone remembering to.")
    @WriteAudited(skip = true, reason = "service emits CONFIGURATION_CHANGED after commit with from/to")
    @PutMapping("/departments/{departmentId}/default-sensitivity")
    @PreAuthorize(DEPARTMENT_ROLES)
    public ResponseEntity<SensitivityTagResponseDTO> setDepartmentDefault(
            @PathVariable UUID departmentId,
            @Valid @RequestBody SensitivityTagRequestDTO body,
            Authentication auth) {
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok(taggingService.setDepartmentDefault(departmentId, body.category(), actorUserId));
    }
}
