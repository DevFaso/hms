package com.example.hms.service;

import static com.example.hms.config.SecurityConstants.*;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.mapper.LabTestDefinitionMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabTestDefinitionApprovalRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.LabTestReferenceRangeDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.terminology.TerminologyCodes;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.UnitValue;
import com.example.hms.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LabTestDefinitionServiceImpl implements LabTestDefinitionService {

    private static final String LAB_TEST_DEFINITION_NOT_FOUND = "labtestdefinition.notfound";
    private static final String CURRENT_USER_RESOLUTION_ERROR = "Unable to resolve current user context";
    private static final String ASSIGNMENT_NOT_FOUND = "assignment.notfound";

    private final LabTestDefinitionRepository repository;
    private final LabTestDefinitionMapper mapper;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final HospitalRepository hospitalRepository;
    private final ActingScopeResolver actingScopeResolver;

    /** Roles that may author or edit a definition AT a hospital (D30: checked at that hospital). */
    private static final Set<String> HOSPITAL_MANAGE_ROLES = Set.of(
        ROLE_HOSPITAL_ADMIN, ROLE_LAB_MANAGER, ROLE_SUPER_ADMIN, ROLE_LAB_SCIENTIST, ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER);

    @Override
    public LabTestDefinitionResponseDTO create(LabTestDefinitionRequestDTO dto) {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null) {
            throw new AccessDeniedException("Unauthenticated access to lab test creation");
        }

        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new AccessDeniedException(CURRENT_USER_RESOLUTION_ERROR));

        // D30: a definition belongs to the hospital it was authored at. It was
        // saved with hospital_id NULL whatever the caller sent - a global row
        // every other tenant read, whose own author then got 403 on its
        // reference ranges because global rows are super-admin only.
        UserRoleHospitalAssignment assignment = null;
        Hospital hospital = null;
        if (dto.getAssignmentId() != null) {
            assignment = assignmentRepository.findById(dto.getAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException(ASSIGNMENT_NOT_FOUND));
            assertOwnAssignment(currentUser, assignment);
            hospital = assignment.getHospital();
            if (hospital == null) {
                throw new IllegalStateException("Assignment is not linked to a hospital");
            }
            assertWithinActiveScope(currentUser, hospital.getId());
            assertUserCanManageHospitalId(currentUser, hospital.getId());
        } else if (!isSuperAdmin(currentUser)) {
            // No assignment named: the active hospital is the scope. Only a
            // super-admin creates a global (hospital-less) definition.
            UUID activeHospitalId = pinnedHospitalId();
            if (activeHospitalId == null) {
                throw new AccessDeniedException("Only Super Admins may manage global lab test definitions.");
            }
            assertUserCanManageHospitalId(currentUser, activeHospitalId);
            hospital = hospitalRepository.findById(activeHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", activeHospitalId));
        }

        dto.setLoincCode(TerminologyCodes.normalizeAndRequireValidLoinc(dto.getLoincCode()));
        LabTestDefinition entity = mapper.toEntity(dto, assignment);
        entity.setHospital(hospital);
        return mapper.toDto(repository.save(entity));
    }

    @Override
    public LabTestDefinitionResponseDTO getById(UUID id) {
        LabTestDefinition definition = repository.findById(id)
            .filter(this::isVisibleToCaller)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_TEST_DEFINITION_NOT_FOUND));
        return mapper.toDto(definition);
    }

    /**
     * D30: the catalog a caller reads is the global rows plus their active
     * hospital's own. A super-admin in global view (no active hospital) reads
     * every row; anyone else with no active hospital reads the global rows.
     */
    @Override
    public List<LabTestDefinitionResponseDTO> getAll() {
        ActingScope scope = actingScopeResolver.current();
        List<LabTestDefinition> rows = scope instanceof ActingScope.Global
            ? repository.findAll()
            : repository.findVisibleAt(pinnedHospitalIdOf(scope));
        return rows.stream()
                .map(mapper::toDto)
                .toList();
    }

    @Override
    public List<LabTestDefinitionResponseDTO> getActiveByHospital(UUID hospitalId) {
        // The path id is a claim, not a scope.
        if (hospitalId != null && !mayReadHospital(hospitalId)) {
            throw new AccessDeniedException("Not permitted to read this hospital's lab test catalog.");
        }
        List<LabTestDefinition> globalDefinitions = repository.findByHospitalIsNullAndActiveTrue();
        List<LabTestDefinition> hospitalDefinitions = hospitalId != null
            ? repository.findByHospital_IdAndActiveTrue(hospitalId)
            : List.of();

        Map<String, LabTestDefinition> deduplicated = new LinkedHashMap<>();
        hospitalDefinitions.forEach(def -> deduplicated.put(def.getTestCode(), def));
        globalDefinitions.forEach(def -> deduplicated.putIfAbsent(def.getTestCode(), def));

        return deduplicated.values().stream()
                .map(mapper::toDto)
                .toList();
    }

    @Override
    public LabTestDefinitionResponseDTO update(UUID id, LabTestDefinitionRequestDTO dto) {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null) {
            throw new AccessDeniedException("Unauthenticated access to lab test update");
        }
        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new AccessDeniedException(CURRENT_USER_RESOLUTION_ERROR));

        LabTestDefinition existing = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_TEST_DEFINITION_NOT_FOUND));

        assertCanManage(currentUser, existing);

        dto.setLoincCode(TerminologyCodes.normalizeAndRequireValidLoinc(dto.getLoincCode()));

        // D30: an edit never moves a definition between hospitals (or into the
        // global catalog) - it used to null hospital_id on every save. A new
        // owning assignment must be the caller's own, at the same hospital.
        if (dto.getAssignmentId() != null
            && (existing.getAssignment() == null
                || !dto.getAssignmentId().equals(existing.getAssignment().getId()))) {
            UserRoleHospitalAssignment assignment = assignmentRepository.findById(dto.getAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException(ASSIGNMENT_NOT_FOUND));
            assertOwnAssignment(currentUser, assignment);
            UUID assignmentHospitalId = assignment.getHospital() != null ? assignment.getHospital().getId() : null;
            UUID definitionHospitalId = existing.getHospital() != null ? existing.getHospital().getId() : null;
            if (!Objects.equals(assignmentHospitalId, definitionHospitalId)) {
                throw new AccessDeniedException("A lab test definition cannot be moved to another hospital.");
            }
            existing.setAssignment(assignment);
        }
        if (dto.getReferenceRanges() != null) {
            assertRangesEditable(existing, mapper.mapRangeDtosToEntities(dto.getReferenceRanges()));
        }
        // Activation belongs to the approval workflow: an edit may switch a
        // definition off, never on (QA D159, same bypass as the ranges).
        if (Boolean.TRUE.equals(dto.getActive()) && !existing.isActive()) {
            throw new IllegalStateException(
                "A lab test definition is activated through its approval workflow, not by editing it.");
        }
        mapper.updateEntityFromDto(dto, existing);
        return mapper.toDto(repository.save(existing));
    }

    @Override
    public void delete(UUID id) {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null) {
            throw new AccessDeniedException("Unauthenticated access to lab test deletion");
        }
        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new AccessDeniedException(CURRENT_USER_RESOLUTION_ERROR));

        LabTestDefinition existing = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_TEST_DEFINITION_NOT_FOUND));
        assertCanManage(currentUser, existing);
        Objects.requireNonNull(existing, "Lab Test Definition reference cannot be null");
        repository.delete(existing);
    }

    @Override
    public Page<LabTestDefinitionResponseDTO> search(String keyword, String unit, String category, Boolean active, LabTestDefinitionApprovalStatus approvalStatus, Pageable pageable) {
        Pageable normalizedPageable = normalizePageable(pageable);
        ActingScope scope = actingScopeResolver.current();
        UUID activeHospitalId = pinnedHospitalIdOf(scope);
        boolean everyHospital = scope instanceof ActingScope.Global;
        return repository.search(keyword, unit, category, active, approvalStatus,
                everyHospital, activeHospitalId, normalizedPageable)
                .map(mapper::toDto);
    }

    private Pageable normalizePageable(Pageable pageable) {
        if (pageable == null) {
            return Pageable.unpaged();
        }

        if (pageable.isUnpaged()) {
            return pageable;
        }

        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            return pageable;
        }

        List<Sort.Order> mappedOrders = sort.stream()
            .map(order -> {
                String property = order.getProperty();
                String mappedProperty = mapSortProperty(property);
                if (mappedProperty.equals(property)) {
                    return order;
                }

                Sort.Order mapped = new Sort.Order(order.getDirection(), mappedProperty)
                    .with(order.getNullHandling());
                if (order.isIgnoreCase()) {
                    mapped = mapped.ignoreCase();
                }
                return mapped;
            })
            .toList();

        if (mappedOrders.isEmpty()) {
            return pageable;
        }

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(mappedOrders));
    }

    private String mapSortProperty(String property) {
        if (property == null) {
            return "name";
        }
        return switch (property.toLowerCase()) {
            case "testname" -> "name";
            case "isactive" -> "active";
            default -> property;
        };
    }

    /** D30: authority over a definition is decided at ITS hospital, not by who created it. */
    private void assertCanManage(User currentUser, LabTestDefinition definition) {
        if (isGlobalDefinition(definition)) {
            assertUserCanManageGlobal(currentUser);
        } else {
            assertUserCanManageHospitalId(currentUser, definition.getHospital().getId());
        }
    }

    private void assertUserCanManageHospitalId(User currentUser, UUID hospitalId) {
        if (isSuperAdmin(currentUser)) {
            return;
        }
        if (hospitalId == null || !assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                currentUser.getId(), hospitalId, HOSPITAL_MANAGE_ROLES)) {
            throw new AccessDeniedException("Unauthorized to manage lab tests for this hospital.");
        }
    }

    /** The assignment a definition is filed under must be the caller's own (a super-admin may name any). */
    private void assertOwnAssignment(User currentUser, UserRoleHospitalAssignment assignment) {
        if (isSuperAdmin(currentUser)) {
            return;
        }
        if (assignment.getUser() == null || !currentUser.getId().equals(assignment.getUser().getId())) {
            throw new AccessDeniedException("Unauthorized to manage lab tests for this hospital.");
        }
    }

    /** A caller pinned to a hospital may not author into another one by naming its assignment. */
    private void assertWithinActiveScope(User currentUser, UUID hospitalId) {
        UUID activeHospitalId = pinnedHospitalId();
        if (activeHospitalId != null && !activeHospitalId.equals(hospitalId) && !isSuperAdmin(currentUser)) {
            throw new AccessDeniedException("The assignment does not belong to the active hospital.");
        }
    }

    /** The hospital this request acts at, or {@code null} for global view and every refusal. */
    private UUID pinnedHospitalId() {
        return pinnedHospitalIdOf(actingScopeResolver.current());
    }

    private static UUID pinnedHospitalIdOf(ActingScope scope) {
        return scope instanceof ActingScope.Pinned pinned ? pinned.hospitalId() : null;
    }

    private boolean isSuperAdmin(User user) {
        return hasAnyRole(user, Set.of(ROLE_SUPER_ADMIN, "SUPER_ADMIN"));
    }

    private boolean isVisibleToCaller(LabTestDefinition definition) {
        if (definition.getHospital() == null) {
            return true;
        }
        return mayReadHospital(definition.getHospital().getId());
    }

    private boolean mayReadHospital(UUID hospitalId) {
        ActingScope scope = actingScopeResolver.current();
        return scope instanceof ActingScope.Global || hospitalId.equals(pinnedHospitalIdOf(scope));
    }

    private void assertUserCanManageGlobal(User currentUser) {
        if (!hasAnyRole(currentUser, Set.of(ROLE_SUPER_ADMIN, "SUPER_ADMIN"))) {
            throw new AccessDeniedException("Only Super Admins may manage global lab test definitions.");
        }
    }

    private boolean hasAnyRole(User user, Set<String> targetRoles) {
        if (user == null || user.getUserRoles() == null || targetRoles == null || targetRoles.isEmpty()) {
            return false;
        }

        return user.getUserRoles().stream()
            .map(UserRole::getRole)
            .filter(Objects::nonNull)
            .anyMatch(role -> matchesRole(role.getCode(), targetRoles) || matchesRole(role.getName(), targetRoles));
    }

    private boolean matchesRole(String value, Set<String> targets) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toUpperCase();
        return targets.stream().anyMatch(target -> target.equalsIgnoreCase(normalized));
    }

    private boolean isGlobalDefinition(LabTestDefinition definition) {
        return definition == null || definition.getHospital() == null;
    }

    @Override
    public LabTestDefinitionResponseDTO processApprovalAction(UUID id, LabTestDefinitionApprovalRequestDTO dto) {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null) {
            throw new AccessDeniedException("Unauthenticated access to approval action");
        }
        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new AccessDeniedException(CURRENT_USER_RESOLUTION_ERROR));

        LabTestDefinition definition = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_TEST_DEFINITION_NOT_FOUND));

        String action = dto.getAction() == null ? "" : dto.getAction().trim().toUpperCase(Locale.ROOT);

        switch (action) {
            case "SUBMIT_FOR_QA" -> {
                assertHasAnyRoleAt(currentUser, definition, Set.of(ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER, ROLE_LAB_DIRECTOR, ROLE_SUPER_ADMIN));
                assertInStatus(definition, EnumSet.of(LabTestDefinitionApprovalStatus.DRAFT, LabTestDefinitionApprovalStatus.REJECTED));
                definition.setApprovalStatus(LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW);
                definition.setRejectionReason(null);
            }
            case "COMPLETE_QA_REVIEW" -> {
                assertHasAnyRoleAt(currentUser, definition, Set.of(ROLE_QUALITY_MANAGER, ROLE_LAB_DIRECTOR, ROLE_SUPER_ADMIN));
                assertInStatus(definition, EnumSet.of(LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW));
                definition.setApprovalStatus(LabTestDefinitionApprovalStatus.PENDING_DIRECTOR_APPROVAL);
                definition.setReviewedById(currentUser.getId());
                definition.setReviewedAt(LocalDateTime.now());
            }
            case "APPROVE" -> {
                assertHasAnyRoleAt(currentUser, definition, Set.of(ROLE_LAB_DIRECTOR, ROLE_SUPER_ADMIN));
                assertInStatus(definition, EnumSet.of(LabTestDefinitionApprovalStatus.PENDING_DIRECTOR_APPROVAL));
                definition.setApprovalStatus(LabTestDefinitionApprovalStatus.APPROVED);
                definition.setApprovedById(currentUser.getId());
                definition.setApprovedAt(LocalDateTime.now());
            }
            case "ACTIVATE" -> {
                assertHasAnyRoleAt(currentUser, definition, Set.of(ROLE_LAB_DIRECTOR, ROLE_LAB_MANAGER, ROLE_SUPER_ADMIN));
                assertInStatus(definition, EnumSet.of(LabTestDefinitionApprovalStatus.APPROVED));
                definition.setApprovalStatus(LabTestDefinitionApprovalStatus.ACTIVE);
                definition.setActive(true);
            }
            case "REJECT" -> {
                assertHasAnyRoleAt(currentUser, definition, Set.of(ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER, ROLE_SUPER_ADMIN));
                assertInStatus(definition, EnumSet.of(
                    LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW,
                    LabTestDefinitionApprovalStatus.PENDING_DIRECTOR_APPROVAL));
                if (dto.getRejectionReason() == null || dto.getRejectionReason().isBlank()) {
                    throw new IllegalArgumentException("A rejection reason is required.");
                }
                definition.setApprovalStatus(LabTestDefinitionApprovalStatus.REJECTED);
                definition.setRejectionReason(dto.getRejectionReason());
                definition.setActive(false);
            }
            case "RETIRE" -> {
                assertHasAnyRoleAt(currentUser, definition, Set.of(ROLE_LAB_DIRECTOR, ROLE_SUPER_ADMIN));
                assertInStatus(definition, EnumSet.of(LabTestDefinitionApprovalStatus.ACTIVE, LabTestDefinitionApprovalStatus.APPROVED));
                definition.setApprovalStatus(LabTestDefinitionApprovalStatus.RETIRED);
                definition.setActive(false);
            }
            default -> throw new IllegalArgumentException("Unknown approval action: " + dto.getAction());
        }

        return mapper.toDto(repository.save(definition));
    }

    /**
     * D30: the approval role must be held AT the definition's hospital — a
     * director at hospital B does not approve hospital A's catalog. A global
     * definition's workflow stays with the super-admin.
     */
    private void assertHasAnyRoleAt(User user, LabTestDefinition definition, Set<String> roles) {
        if (isSuperAdmin(user)) {
            return;
        }
        if (isGlobalDefinition(definition)
            || !assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                user.getId(), definition.getHospital().getId(), roles)) {
            throw new AccessDeniedException("Insufficient role to perform this approval action.");
        }
    }

    /** Statuses whose reference ranges were reviewed and may not move without a new review (QA D159). */
    private static final EnumSet<LabTestDefinitionApprovalStatus> RANGES_LOCKED = EnumSet.of(
        LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW,
        LabTestDefinitionApprovalStatus.PENDING_DIRECTOR_APPROVAL,
        LabTestDefinitionApprovalStatus.APPROVED,
        LabTestDefinitionApprovalStatus.ACTIVE,
        LabTestDefinitionApprovalStatus.RETIRED);

    /**
     * QA D159: the reference ranges are what QA and the director approved,
     * and they grade every result. Changing them on a definition under
     * review, approved or live would put unreviewed ranges in front of
     * clinicians, so only a draft or a rejected definition takes new ones.
     * Re-sending the same ranges (an edit of the name, say) is not a change.
     */
    private void assertRangesEditable(LabTestDefinition definition, List<LabTestReferenceRange> proposed) {
        if (!RANGES_LOCKED.contains(definition.getApprovalStatus())) {
            return;
        }
        List<LabTestReferenceRange> current = definition.getReferenceRanges() == null
            ? List.of() : definition.getReferenceRanges();
        List<LabTestReferenceRange> next = proposed == null ? List.of() : proposed;
        if (!current.equals(next)) {
            throw new IllegalStateException(
                "The reference ranges of a definition that is " + definition.getApprovalStatus()
                + " cannot be changed without a new approval: retire it and submit a new definition for review.");
        }
    }

    private void assertInStatus(LabTestDefinition definition, EnumSet<LabTestDefinitionApprovalStatus> allowed) {
        if (!allowed.contains(definition.getApprovalStatus())) {
            throw new IllegalStateException(
                "Action not allowed from status: " + definition.getApprovalStatus()
                + ". Allowed: " + allowed);
        }
    }

    @Override
    public LabTestDefinitionResponseDTO updateReferenceRanges(UUID id, List<LabTestReferenceRangeDTO> ranges) {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null) {
            throw new AccessDeniedException("Unauthenticated access to reference range update");
        }
        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new AccessDeniedException(CURRENT_USER_RESOLUTION_ERROR));

        LabTestDefinition definition = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_TEST_DEFINITION_NOT_FOUND));

        assertCanManage(currentUser, definition);

        List<LabTestReferenceRange> entityRanges = mapper.mapRangeDtosToEntities(ranges);
        assertRangesEditable(definition, entityRanges);

        definition.setReferenceRanges(entityRanges);
        return mapper.toDto(repository.save(definition));
    }

    @Override
    public byte[] exportPdf() {
        // D30: the export follows the acting scope. A pinned scope exports that
        // hospital plus the global catalog; a super-admin in global view exports
        // every hospital (never a stale active hospital that is not pinned);
        // anyone else without a pin gets the global catalog.
        ActingScope scope = actingScopeResolver.current();
        UUID hospitalId = pinnedHospitalIdOf(scope);
        List<LabTestDefinition> definitions;
        if (hospitalId != null) {
            definitions = repository.findByHospital_IdAndActiveTrue(hospitalId);
        } else {
            List<LabTestDefinition> unscoped = scope instanceof ActingScope.Global
                ? repository.findAll()
                : repository.findVisibleAt(null);
            definitions = unscoped.stream()
                .filter(LabTestDefinition::isActive)
                .toList();
        }

        // Include global definitions when hospital-scoped
        if (hospitalId != null) {
            List<LabTestDefinition> globalDefs = repository.findByHospitalIsNullAndActiveTrue();
            java.util.Map<String, LabTestDefinition> merged = new java.util.LinkedHashMap<>();
            definitions.forEach(d -> merged.put(d.getTestCode(), d));
            globalDefs.forEach(d -> merged.putIfAbsent(d.getTestCode(), d));
            definitions = new ArrayList<>(merged.values());
        }

        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf);

            doc.add(new Paragraph("Lab Test Definitions Report")
                .setFontSize(18).setBold());
            doc.add(new Paragraph("Generated: " + java.time.LocalDate.now())
                .setFontSize(10));
            doc.add(new Paragraph(" "));

            float[] colWidths = {1, 2, 1.5f, 1, 1, 1, 1};
            Table table = new Table(UnitValue.createPercentArray(colWidths))
                .useAllAvailableWidth();

            String[] headers = {"Test Code", "Name", "Category", "Unit", "Sample Type", "Status", "TAT (min)"};
            for (String h : headers) {
                table.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(9)));
            }

            for (LabTestDefinition def : definitions) {
                table.addCell(new Cell().add(new Paragraph(safe(def.getTestCode())).setFontSize(8)));
                table.addCell(new Cell().add(new Paragraph(safe(def.getName())).setFontSize(8)));
                table.addCell(new Cell().add(new Paragraph(safe(def.getCategory())).setFontSize(8)));
                table.addCell(new Cell().add(new Paragraph(safe(def.getUnit())).setFontSize(8)));
                table.addCell(new Cell().add(new Paragraph(safe(def.getSampleType())).setFontSize(8)));
                table.addCell(new Cell().add(new Paragraph(def.getApprovalStatus() != null ? def.getApprovalStatus().name() : "").setFontSize(8)));
                table.addCell(new Cell().add(new Paragraph(def.getTurnaroundTimeMinutes() != null ? String.valueOf(def.getTurnaroundTimeMinutes()) : "").setFontSize(8)));
            }

            doc.add(table);
            doc.close();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to generate PDF export", e);
        }
    }

    private static String safe(String val) {
        return val != null ? val : "";
    }
}
