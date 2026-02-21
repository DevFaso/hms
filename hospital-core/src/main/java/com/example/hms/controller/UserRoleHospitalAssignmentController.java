package com.example.hms.controller;

import com.example.hms.payload.dto.AssignmentMinimalDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentResponseDTO;
import com.example.hms.payload.dto.assignment.AssignmentSearchCriteria;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBatchResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentConfirmationRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentMultiRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentPublicViewDTO;
import com.example.hms.service.UserRoleHospitalAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
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
@RequestMapping("/assignments")
@RequiredArgsConstructor
@Slf4j
public class UserRoleHospitalAssignmentController {

    private final UserRoleHospitalAssignmentService assignmentService;

    @Operation(summary = "Assign a user and role across multiple hospitals or organizations")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @PostMapping("/multi-scope")
    public ResponseEntity<UserRoleAssignmentBatchResponseDTO> assignAcrossMultipleScopes(
            @Valid @RequestBody UserRoleAssignmentMultiRequestDTO requestDTO) {
    String roleIdentifier = requestDTO.getRoleId() != null
        ? String.valueOf(requestDTO.getRoleId())
        : requestDTO.getRoleName();
    String userIdentifier = requestDTO.getUserId() != null
        ? String.valueOf(requestDTO.getUserId())
        : requestDTO.getUserIdentifier();
    log.info("🌐 Assigning role '{}' across multiple scopes for user '{}'", roleIdentifier, userIdentifier);
        return ResponseEntity.ok(assignmentService.assignRoleToMultipleScopes(requestDTO));
    }

    @Operation(summary = "Assign a role to a user in a hospital")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @PostMapping
    public ResponseEntity<UserRoleHospitalAssignmentResponseDTO> createAssignment(
            @Valid @RequestBody UserRoleHospitalAssignmentRequestDTO requestDTO) {
        log.info("🛠 Creating assignment for user {}", requestDTO.getUserId());
        return ResponseEntity.ok(assignmentService.assignRole(requestDTO));
    }

    @Operation(summary = "Update an existing assignment")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<UserRoleHospitalAssignmentResponseDTO> updateAssignment(
            @PathVariable UUID id,
            @Valid @RequestBody UserRoleHospitalAssignmentRequestDTO requestDTO) {
        log.info("🔄 Updating assignment {}", id);
        return ResponseEntity.ok(assignmentService.updateAssignment(id, requestDTO));
    }

    @Operation(summary = "Get a specific assignment by ID")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<UserRoleHospitalAssignmentResponseDTO> getAssignment(@PathVariable UUID id) {
        log.info("📥 Fetching assignment by ID {}", id);
        return ResponseEntity.ok(assignmentService.getAssignmentById(id));
    }

    @Operation(summary = "Regenerate an assignment code and optionally resend notifications")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @PostMapping("/{assignmentId}/regenerate-code")
    public ResponseEntity<UserRoleHospitalAssignmentResponseDTO> regenerateAssignmentCode(
            @PathVariable UUID assignmentId,
            @Parameter(description = "Send notification emails/SMS after regeneration", example = "true")
            @RequestParam(defaultValue = "true") boolean resendNotifications) {
        log.info("🔁 Regenerating assignment code for assignment '{}' (resendNotifications={})", assignmentId, resendNotifications);
        return ResponseEntity.ok(assignmentService.regenerateAssignmentCode(assignmentId, resendNotifications));
    }

    @Operation(summary = "Confirm an assignment using the registrar's confirmation code")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @PostMapping("/{assignmentId}/confirm")
    public ResponseEntity<UserRoleHospitalAssignmentResponseDTO> confirmAssignment(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody UserRoleAssignmentConfirmationRequestDTO requestDTO) {
        log.info("✅ Confirming assignment '{}'", assignmentId);
        return ResponseEntity.ok(assignmentService.confirmAssignment(assignmentId, requestDTO.getConfirmationCode()));
    }

    @Operation(summary = "Get all role assignments with pagination")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @GetMapping
    public ResponseEntity<Page<UserRoleHospitalAssignmentResponseDTO>> getAllAssignments(
            @Parameter(description = "Page number", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size", example = "20")
            @RequestParam(defaultValue = "20") int size,

            @Parameter(description = "Filter by hospital ID")
            @RequestParam(required = false) UUID hospitalId,

            @Parameter(description = "Filter by active state")
            @RequestParam(required = false) Boolean active,

            @Parameter(description = "Free text search across user, role, hospital and assignment code")
            @RequestParam(required = false) String search,

            @Parameter(description = "Filter by assignment code")
            @RequestParam(required = false) String assignmentCode
    ) {
        Pageable pageable = PageRequest.of(page, size);
        AssignmentSearchCriteria criteria = AssignmentSearchCriteria.builder()
            .hospitalId(hospitalId != null ? hospitalId.toString() : null)
            .active(active)
            .search(StringUtils.hasText(search) ? search.trim() : null)
            .assignmentCode(StringUtils.hasText(assignmentCode) ? assignmentCode.trim() : null)
            .build();

        log.info("📄 Fetching assignments (page {}, size {}, hospitalId={}, active={}, search='{}', assignmentCode='{}')",
            page, size, hospitalId, active, search, assignmentCode);
        Page<UserRoleHospitalAssignmentResponseDTO> resultPage =
                assignmentService.getAllAssignments(pageable, criteria);
        return ResponseEntity.ok(resultPage);
    }

    @Operation(summary = "Get minimal assignments for dropdowns")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/minimal")
    public ResponseEntity<List<AssignmentMinimalDTO>> getMinimalAssignments() {
        log.info("📥 Fetching minimal assignments for dropdown");
        return ResponseEntity.ok(assignmentService.getMinimalAssignments());
    }

    @Operation(summary = "Public view of an assignment by assignment code")
    @PreAuthorize("permitAll()")
    @GetMapping("/public/{assignmentCode}")
    public ResponseEntity<UserRoleAssignmentPublicViewDTO> getPublicAssignment(@PathVariable String assignmentCode) {
        log.info("🌐 Fetching public assignment view for code '{}'", assignmentCode);
        return ResponseEntity.ok(assignmentService.getAssignmentPublicView(assignmentCode));
    }

    @Operation(summary = "Self-service verification: assignee submits their 6-digit confirmation code")
    @PostMapping("/public/{assignmentCode}/verify")
    public ResponseEntity<UserRoleAssignmentPublicViewDTO> verifyAssignmentByCode(
            @PathVariable String assignmentCode,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "JSON body with confirmationCode field")
            @RequestBody java.util.Map<String, String> body) {
        String confirmationCode = body != null ? body.get("confirmationCode") : null;
        log.info("🔐 Self-service verification attempt for assignment code '{}'", assignmentCode);
        return ResponseEntity.ok(assignmentService.verifyAssignmentByCode(assignmentCode, confirmationCode));
    }

    @Operation(summary = "Delete an assignment by ID")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAssignment(@PathVariable UUID id) {
        log.warn("❌ Deleting assignment ID {}", id);
        assignmentService.deleteAssignment(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete all assignments for a specific user")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @DeleteMapping("/user/{userId}")
    public ResponseEntity<Void> deleteAllForUser(@PathVariable UUID userId) {
        log.warn("🗑️ Deleting all assignments for user ID {}", userId);
        assignmentService.deleteAllAssignmentsForUser(userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete a role if it's not assigned")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @DeleteMapping("/role/{roleId}")
    public ResponseEntity<Void> deleteRoleIfUnassigned(@PathVariable UUID roleId) {
        log.warn("🔐 Attempting to delete role ID {}", roleId);
        assignmentService.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Bulk import assignments via CSV payload")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")
    @PostMapping("/bulk-import")
    public ResponseEntity<UserRoleAssignmentBulkImportResponseDTO> bulkImportAssignments(
            @Valid @RequestBody UserRoleAssignmentBulkImportRequestDTO requestDTO) {
        log.info("📦 Bulk importing assignments (delimiter='{}')", requestDTO.getDelimiter());
        return ResponseEntity.ok(assignmentService.bulkImportAssignments(requestDTO));
    }
}
