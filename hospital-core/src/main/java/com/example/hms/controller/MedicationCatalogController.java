package com.example.hms.controller;

import com.example.hms.security.audit.WriteAudited;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.enums.MedicationCatalogVersionKind;
import com.example.hms.enums.MedicationCatalogVersionStatus;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogVersionDecisionDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogVersionResponseDTO;
import com.example.hms.service.formulary.MedicationCatalogVersionService;
import com.example.hms.service.MedicationCatalogItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/medication-catalog")
@RequiredArgsConstructor
@Tag(name = "Medication Catalog", description = "Manage the hospital medication formulary")
@SecurityRequirement(name = "bearerAuth")
public class MedicationCatalogController {

    private final MedicationCatalogItemService catalogService;
    private final ControllerAuthUtils authUtils;
    /** Go-live D2 (V385): the approval circuit of the catalogue (queue, history, decisions). */
    private final MedicationCatalogVersionService versionService;

    /** Who reads the approval queue and an entry's history: those who propose and those who decide. */
    private static final String VERSION_READERS = "hasAnyAuthority('ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN',"
        + "'ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON','ROLE_SUPER_ADMIN')";
    /**
     * Who may reach a decision at all. Which of them decides WHICH version, at which hospital, and never their own,
     * is {@code MedicationCatalogApprovalPolicy}'s: a pharmacist of the hospital; for dose limits the médecin-chef
     * too; a platform administrator for a platform entry only.
     */
    private static final String VERSION_DECIDERS = "hasAnyAuthority('ROLE_PHARMACIST','ROLE_DOCTOR',"
        + "'ROLE_PHYSICIAN','ROLE_SURGEON','ROLE_SUPER_ADMIN')";
    private static final String VERSION_AUDITED = "service audits each step of the approval circuit";

    /**
     * Go-live D2 (V385): a new entry is proposed, not created: 202 with the version awaiting approval. It is in
     * the catalogue, and can be prescribed and dispensed, once a pharmacist other than its author approved it.
     */
    @PostMapping
    // P-02: formulary additions are governance acts — restrict to admin roles only.
    @WriteAudited(skip = true, reason = VERSION_AUDITED)
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Propose a new medication for the catalog (awaits approval)")
    public ResponseEntity<MedicationCatalogVersionResponseDTO> create(@Valid @RequestBody MedicationCatalogItemRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(catalogService.create(dto));
    }

    @PostMapping("/{id}/approval-request")
    @WriteAudited(skip = true, reason = VERSION_AUDITED)
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Submit an entry nobody approved yet, as it stands, for approval")
    public ResponseEntity<MedicationCatalogItemResponseDTO> requestApproval(
            @PathVariable UUID id,
            @Valid @RequestBody MedicationCatalogVersionDecisionDTO dto) {
        return ResponseEntity.ok(catalogService.requestFormularyApproval(id, dto.getReason()));
    }

    @GetMapping("/versions")
    @PreAuthorize(VERSION_READERS)
    @Operation(summary = "The approval queue, or the decided versions, of the acting hospital")
    public ResponseEntity<Page<MedicationCatalogVersionResponseDTO>> listVersions(
            @RequestParam(required = false) MedicationCatalogVersionStatus status,
            @RequestParam(required = false) MedicationCatalogVersionKind kind,
            Pageable pageable) {
        return ResponseEntity.ok(versionService.list(status, kind, pageable));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize(VERSION_READERS)
    @Operation(summary = "The version history of one catalog entry: who, when, why")
    public ResponseEntity<List<MedicationCatalogVersionResponseDTO>> history(@PathVariable UUID id) {
        return ResponseEntity.ok(versionService.history(id));
    }

    @PostMapping("/versions/{versionId}/approve")
    @WriteAudited(skip = true, reason = VERSION_AUDITED)
    @PreAuthorize(VERSION_DECIDERS)
    @Operation(summary = "Approve a version: it takes effect now")
    public ResponseEntity<MedicationCatalogVersionResponseDTO> approve(
            @PathVariable UUID versionId,
            @Valid @RequestBody(required = false) MedicationCatalogVersionDecisionDTO dto) {
        return ResponseEntity.ok(versionService.approve(versionId, dto == null ? null : dto.getReason()));
    }

    @PostMapping("/versions/{versionId}/reject")
    @WriteAudited(skip = true, reason = VERSION_AUDITED)
    @PreAuthorize(VERSION_DECIDERS)
    @Operation(summary = "Refuse a version, with the reason")
    public ResponseEntity<MedicationCatalogVersionResponseDTO> reject(
            @PathVariable UUID versionId,
            @Valid @RequestBody MedicationCatalogVersionDecisionDTO dto) {
        return ResponseEntity.ok(versionService.reject(versionId, dto.getReason()));
    }

    @PostMapping("/versions/{versionId}/withdraw")
    @WriteAudited(skip = true, reason = VERSION_AUDITED)
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Take back one's own version while it awaits a decision")
    public ResponseEntity<MedicationCatalogVersionResponseDTO> withdraw(@PathVariable UUID versionId) {
        return ResponseEntity.ok(versionService.withdraw(versionId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    @Operation(summary = "Get a medication catalog item by ID")
    public ResponseEntity<MedicationCatalogItemResponseDTO> getById(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID hospitalId,
            Authentication auth) {
        // r66 D2901: the hospital is the caller's acting scope, as on list and search (the portal sends none).
        return ResponseEntity.ok(catalogService.getById(id, resolveCatalogScope(auth, hospitalId)));
    }

    @GetMapping
    // QA D965: the inventory page's "Add item" lists the catalogue for the inventory clerk too.
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_INVENTORY_CLERK','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List active medications",
        description = "Returns active medications for the resolved hospital scope. "
            + "Super-admins in global view (no JWT scope, no hospitalId param) "
            + "see every active catalog entry across tenants.")
    public ResponseEntity<Page<MedicationCatalogItemResponseDTO>> list(
            @RequestParam(required = false) UUID hospitalId,
            Pageable pageable,
            Authentication auth) {
        return ResponseEntity.ok(catalogService.listByHospital(resolveCatalogScope(auth, hospitalId), pageable));
    }

    @GetMapping("/search")
    // QA D965: the catalogue page's search box, offered to the super-admin the list admits.
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Search medications by name, ATC code, or brand")
    public ResponseEntity<Page<MedicationCatalogItemResponseDTO>> search(
            @RequestParam(required = false) UUID hospitalId,
            @RequestParam String q,
            Pageable pageable,
            Authentication auth) {
        // QA D922: the hospital is the caller's acting scope, as on list. The
        // portal never sent hospitalId, so every search answered 400 (the
        // catalogue page's search box and the prescribing form's picker), and
        // a caller-supplied id was searched whatever tenant it named.
        return ResponseEntity.ok(catalogService.search(resolveCatalogScope(auth, hospitalId), q, pageable));
    }

    /**
     * The hospital a catalogue read is scoped to. Same super-admin global-view
     * pattern as PharmacyRegistryController.list and InBasketController (PR
     * #292): {@code null} only for a verified super-admin with no hospital
     * pinned (the whole platform catalogue); everyone else is refused without
     * a scope. One rule for list, search and category (review of #975: the
     * search refused the super-admin's global view the list served).
     */
    private UUID resolveCatalogScope(Authentication auth, UUID requestedHospitalId) {
        UUID resolved = authUtils.resolveHospitalScope(auth, requestedHospitalId, false);
        if (resolved == null && !authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")) {
            throw new BusinessException("pharmacy.hospitalContext.required");
        }
        return resolved;
    }

    @GetMapping("/category/{category}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    @Operation(summary = "List medications by category")
    public ResponseEntity<Page<MedicationCatalogItemResponseDTO>> listByCategory(
            @PathVariable String category,
            @RequestParam(required = false) UUID hospitalId,
            Pageable pageable,
            Authentication auth) {
        return ResponseEntity.ok(catalogService.listByCategory(resolveCatalogScope(auth, hospitalId), category, pageable));
    }

    @PutMapping("/{id}")
    // P-02: formulary edits are governance acts — restrict to admin roles only.
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update a medication catalog item")
    public ResponseEntity<MedicationCatalogItemResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody MedicationCatalogItemRequestDTO dto,
            Authentication auth) {
        // r66 D2901: the portal names no hospital and the service acts at the caller's. A hospital named in
        // the BODY is not narrowed by the interceptor, so it is narrowed here: one the caller may not act
        // at is refused, and a super-admin naming hospital B acts (and is audited) at B.
        if (dto.getHospitalId() != null) {
            resolveCatalogScope(auth, dto.getHospitalId());
        }
        return ResponseEntity.ok(catalogService.update(id, dto));
    }

    /**
     * Round 18 (QA D935): the paediatric dose limits are the pharmacy's to set
     * and approve, as a real formulary is (the round-17 QA found the
     * pharmacist could not edit them at all). Scoped to the caller's acting
     * hospital; the service audits MEDICATION_DOSE_LIMITS_UPDATED.
     *
     * <p>Go-live D2 (V385): the limits sent are PROPOSED. They are in force once a different pharmacist, or the
     * médecin-chef, approved the version; until then the dose check keeps reading the limits in force.
     */
    @PutMapping("/{id}/pediatric-dosing")
    @WriteAudited(skip = true, reason = "service emits MEDICATION_DOSE_LIMITS_UPDATED")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Propose a medication's paediatric dose limits (awaits approval)")
    public ResponseEntity<MedicationCatalogItemResponseDTO> updatePediatricDosing(
            @PathVariable UUID id,
            @Valid @RequestBody com.example.hms.payload.dto.medication.PediatricDosingRequestDTO dto) {
        return ResponseEntity.ok(catalogService.updatePediatricDosing(id, dto));
    }

    @DeleteMapping("/{id}")
    // P-02: formulary deactivation is a governance act — restrict to admin roles only.
    @WriteAudited(skip = true, reason = "service emits MEDICATION_DEACTIVATED")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Deactivate a medication catalog item")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID hospitalId) {
        // r66 D2901: the portal sends no hospitalId (every deactivation answered 400); the service acts at
        // the caller's hospital. One that is sent is narrowed by HospitalIdNarrowingInterceptor first.
        catalogService.deactivate(id, hospitalId);
        return ResponseEntity.noContent().build();
    }
}
