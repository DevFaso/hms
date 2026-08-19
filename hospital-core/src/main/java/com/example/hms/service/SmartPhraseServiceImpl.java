package com.example.hms.service;

import com.example.hms.enums.SmartPhraseScope;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.UnauthorizedAccessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.SmartPhrase;
import com.example.hms.model.User;
import com.example.hms.payload.dto.SmartPhraseRequestDTO;
import com.example.hms.payload.dto.SmartPhraseResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.SmartPhraseRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class SmartPhraseServiceImpl implements SmartPhraseService {

    private static final String SUPER_ADMIN_AUTHORITY = "ROLE_SUPER_ADMIN";

    /** Persisted role codes that may declare/edit HOSPITAL-scope macros at a hospital. */
    private static final Set<String> HOSPITAL_ADMIN_ROLES =
        Set.of("ROLE_HOSPITAL_ADMIN", SUPER_ADMIN_AUTHORITY);

    private static final String NOT_FOUND_PREFIX = "SmartPhrase not found: ";

    private final SmartPhraseRepository repository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final Clock clock;

    public SmartPhraseServiceImpl(SmartPhraseRepository repository,
                                  HospitalRepository hospitalRepository,
                                  UserRepository userRepository,
                                  UserRoleHospitalAssignmentRepository assignmentRepository,
                                  Clock clock) {
        this.repository = repository;
        this.hospitalRepository = hospitalRepository;
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SmartPhraseResponseDTO create(SmartPhraseRequestDTO request) {
        validateRequest(request);
        User caller = currentUserOrThrow();
        applyOwnershipDefaults(request, caller);
        authorizeForScope(request.getScope(), request.getHospitalId(), caller);

        String normalisedTrigger = request.getTrigger().trim().toLowerCase();
        ensureUniqueTrigger(normalisedTrigger, request, /* excludeId */ null);

        SmartPhrase phrase = SmartPhrase.builder()
            .trigger(normalisedTrigger)
            .title(request.getTitle())
            .expansion(request.getExpansion())
            .scope(request.getScope())
            .hospital(resolveHospital(request))
            .owner(resolveOwner(request))
            .specialty(request.getSpecialty())
            .usageCount(0L)
            .build();
        return toDto(repository.save(phrase));
    }

    @Override
    @Transactional
    public SmartPhraseResponseDTO update(UUID id, SmartPhraseRequestDTO request) {
        validateRequest(request);
        SmartPhrase existing = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_PREFIX + id));
        User caller = currentUserOrThrow();

        // Authorize against the EXISTING macro's scope first — a clinician must not be
        // able to "rebase" a macro they do not own to a scope they DO control.
        UUID existingHospitalId = existing.getHospital() != null ? existing.getHospital().getId() : null;
        authorizeForExisting(existing.getScope(), existingHospitalId,
            existing.getOwner() != null ? existing.getOwner().getId() : null, caller);

        // Then check that the caller can land the macro at the REQUESTED scope.
        applyOwnershipDefaults(request, caller);
        authorizeForScope(request.getScope(), request.getHospitalId(), caller);

        String normalisedTrigger = request.getTrigger().trim().toLowerCase();
        ensureUniqueTrigger(normalisedTrigger, request, existing.getId());

        existing.setTrigger(normalisedTrigger);
        existing.setTitle(request.getTitle());
        existing.setExpansion(request.getExpansion());
        existing.setScope(request.getScope());
        existing.setHospital(resolveHospital(request));
        existing.setOwner(resolveOwner(request));
        existing.setSpecialty(request.getSpecialty());
        return toDto(repository.save(existing));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        SmartPhrase existing = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_PREFIX + id));
        User caller = currentUserOrThrow();
        UUID hid = existing.getHospital() != null ? existing.getHospital().getId() : null;
        UUID oid = existing.getOwner() != null ? existing.getOwner().getId() : null;
        authorizeForExisting(existing.getScope(), hid, oid, caller);
        repository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public SmartPhraseResponseDTO get(UUID id) {
        return repository.findById(id)
            .map(this::toDto)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_PREFIX + id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SmartPhraseResponseDTO> listGlobal(Pageable pageable) {
        return repository.findByScope(SmartPhraseScope.GLOBAL, pageable).map(this::toDto);
    }

    /** Below this length the autocomplete short-circuits without touching the DB. */
    private static final int MIN_AUTOCOMPLETE_PREFIX = 2;

    @Override
    @Transactional(readOnly = true)
    public List<SmartPhraseResponseDTO> autocomplete(String rawPrefix, UUID hospitalId) {
        String prefix = rawPrefix == null ? "" : rawPrefix.trim().toLowerCase();
        // Need at least ".x" — a bare "." would otherwise return the entire visible library.
        if (prefix.length() < MIN_AUTOCOMPLETE_PREFIX || prefix.charAt(0) != '.') {
            return List.of();
        }
        UUID userId = currentUserIdOrNull();
        List<SmartPhrase> hits = repository.searchByTriggerPrefix(prefix, userId, hospitalId);
        return narrowByPrecedence(hits).stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SmartPhraseResponseDTO> findByTrigger(String trigger, UUID hospitalId) {
        if (trigger == null || trigger.isBlank()) {
            return Optional.empty();
        }
        String normalised = trigger.trim().toLowerCase();
        UUID userId = currentUserIdOrNull();
        List<SmartPhrase> hits = repository.searchByTriggerPrefix(normalised, userId, hospitalId);
        return narrowByPrecedence(hits).stream()
            .filter(sp -> sp.getTrigger().equals(normalised))
            .findFirst()
            .map(this::toDto);
    }

    @Override
    @Transactional
    public void recordUsage(UUID id) {
        int updated = repository.incrementUsage(id, LocalDateTime.now(clock));
        if (updated == 0) {
            throw new ResourceNotFoundException(NOT_FOUND_PREFIX + id);
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Apply USER > HOSPITAL > GLOBAL precedence for the same trigger. Stable
     * ordering: most specific scope first within a trigger, then alphabetic
     * across triggers (already enforced by the query's ORDER BY).
     */
    private List<SmartPhrase> narrowByPrecedence(List<SmartPhrase> hits) {
        Map<String, SmartPhrase> byTrigger = new LinkedHashMap<>();
        Map<String, Integer> currentRank = new HashMap<>();
        for (SmartPhrase sp : hits) {
            int rank = scopeRank(sp.getScope());
            Integer prev = currentRank.get(sp.getTrigger());
            if (prev == null || rank > prev) {
                byTrigger.put(sp.getTrigger(), sp);
                currentRank.put(sp.getTrigger(), rank);
            }
        }
        return byTrigger.values().stream()
            .sorted(Comparator.comparing(SmartPhrase::getTrigger))
            .toList();
    }

    private int scopeRank(SmartPhraseScope scope) {
        return switch (scope) {
            case USER -> 3;
            case HOSPITAL -> 2;
            case GLOBAL -> 1;
        };
    }

    private void validateRequest(SmartPhraseRequestDTO request) {
        if (request == null) {
            throw new BusinessException("SmartPhraseRequest is required");
        }
        if (request.getScope() == SmartPhraseScope.GLOBAL
            && (request.getHospitalId() != null || request.getOwnerUserId() != null)) {
            throw new BusinessException("GLOBAL SmartPhrase must not carry hospitalId or ownerUserId");
        }
        if (request.getScope() == SmartPhraseScope.HOSPITAL && request.getHospitalId() == null) {
            throw new BusinessException("HOSPITAL SmartPhrase requires hospitalId");
        }
        if (request.getScope() == SmartPhraseScope.USER && request.getOwnerUserId() == null) {
            throw new BusinessException("USER SmartPhrase requires ownerUserId");
        }
    }

    private void ensureUniqueTrigger(String trigger,
                                     SmartPhraseRequestDTO request,
                                     UUID excludeId) {
        Optional<SmartPhrase> conflict = switch (request.getScope()) {
            case GLOBAL -> repository
                .findFirstByTriggerIgnoreCaseAndScopeAndHospitalIsNullAndOwnerIsNull(
                    trigger, SmartPhraseScope.GLOBAL);
            case HOSPITAL -> repository
                .findFirstByTriggerIgnoreCaseAndScopeAndHospital_IdAndOwnerIsNull(
                    trigger, SmartPhraseScope.HOSPITAL, request.getHospitalId());
            case USER -> request.getHospitalId() == null
                ? repository.findFirstByTriggerIgnoreCaseAndScopeAndHospitalIsNullAndOwner_Id(
                    trigger, SmartPhraseScope.USER, request.getOwnerUserId())
                : repository.findFirstByTriggerIgnoreCaseAndScopeAndHospital_IdAndOwner_Id(
                    trigger, SmartPhraseScope.USER,
                    request.getHospitalId(), request.getOwnerUserId());
        };
        conflict.ifPresent(existing -> {
            if (excludeId == null || !existing.getId().equals(excludeId)) {
                throw new BusinessException(
                    "A SmartPhrase already exists for trigger '" + trigger
                        + "' at scope " + request.getScope());
            }
        });
    }

    private Hospital resolveHospital(SmartPhraseRequestDTO request) {
        if (request.getHospitalId() == null) {
            return null;
        }
        return hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Hospital not found: " + request.getHospitalId()));
    }

    private User resolveOwner(SmartPhraseRequestDTO request) {
        if (request.getOwnerUserId() == null) {
            return null;
        }
        return userRepository.findById(request.getOwnerUserId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "User not found: " + request.getOwnerUserId()));
    }

    private UUID currentUserIdOrNull() {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null || username.isBlank()) {
            return null;
        }
        return userRepository.findByUsernameIgnoreCase(username)
            .map(User::getId)
            .orElse(null);
    }

    private User currentUserOrThrow() {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null || username.isBlank()) {
            throw new UnauthorizedAccessException("No authenticated user in security context.");
        }
        return userRepository.findByUsernameIgnoreCase(username)
            .orElseThrow(() -> new UnauthorizedAccessException(
                "Authenticated user not resolvable: " + username));
    }

    /**
     * Replace the client-supplied {@code ownerUserId} on USER-scope payloads with
     * the authenticated caller. A clinician must never be able to forge ownership
     * of another user's macros by sending an arbitrary UUID in the request body.
     */
    private void applyOwnershipDefaults(SmartPhraseRequestDTO request, User caller) {
        if (request.getScope() == SmartPhraseScope.USER) {
            request.setOwnerUserId(caller.getId());
        } else if (request.getScope() == SmartPhraseScope.GLOBAL) {
            request.setHospitalId(null);
            request.setOwnerUserId(null);
        }
    }

    /**
     * Authorize a request to LAND a macro at the given scope/hospital.
     * <ul>
     *   <li>GLOBAL — only SUPER_ADMIN.</li>
     *   <li>HOSPITAL — SUPER_ADMIN, or HOSPITAL_ADMIN with an active assignment at the target hospital.</li>
     *   <li>USER — any authenticated clinician (the request body has already been forced to {@code caller}).</li>
     * </ul>
     */
    private void authorizeForScope(SmartPhraseScope scope, UUID hospitalId, User caller) {
        switch (scope) {
            case GLOBAL -> requireSuperAdmin("manage GLOBAL SmartPhrase");
            case HOSPITAL -> {
                if (isSuperAdmin()) {
                    return;
                }
                if (hospitalId == null
                    || !assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                        caller.getId(), hospitalId, HOSPITAL_ADMIN_ROLES)) {
                    throw new UnauthorizedAccessException(
                        "Caller lacks HOSPITAL_ADMIN at hospital " + hospitalId
                            + " required to manage a HOSPITAL SmartPhrase.");
                }
            }
            case USER -> {
                // applyOwnershipDefaults forced ownerUserId = caller.id; nothing extra to check.
            }
        }
    }

    /**
     * Authorize the caller against the EXISTING macro for an update or delete.
     * Looser than {@link #authorizeForScope}: a USER macro can be edited by its
     * owner; HOSPITAL and GLOBAL still require admin / super-admin.
     */
    private void authorizeForExisting(SmartPhraseScope scope,
                                      UUID hospitalId,
                                      UUID ownerUserId,
                                      User caller) {
        // Sonar S6884 ("Replace this 'if' statement with a pattern
        // match guard") flagged the if-then-throw inside the HOSPITAL
        // and USER branches. The suggestion does NOT apply here:
        // Java 21 `when` guards only attach to *type patterns* (JEP
        // 441), not to bare enum constant case labels. Attempting
        // `case HOSPITAL when ... ->` is a compile error. Keeping the
        // original shape and tracking this as a Sonar false positive
        // (Pattern 10 in docs/SonarQubeInstructions.md). To formally
        // close the finding, mark as "won't fix" in SonarCloud with
        // a link back to this comment.
        switch (scope) {
            case GLOBAL -> requireSuperAdmin("edit GLOBAL SmartPhrase");
            case HOSPITAL -> {
                if (!isSuperAdmin()
                    && (hospitalId == null
                        || !assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                            caller.getId(), hospitalId, HOSPITAL_ADMIN_ROLES))) {
                    throw new UnauthorizedAccessException(
                        "Caller lacks HOSPITAL_ADMIN at hospital " + hospitalId
                            + " required to edit a HOSPITAL SmartPhrase.");
                }
            }
            case USER -> {
                if (!caller.getId().equals(ownerUserId) && !isSuperAdmin()) {
                    throw new UnauthorizedAccessException(
                        "USER SmartPhrase can only be edited by its owner.");
                }
            }
        }
    }

    private void requireSuperAdmin(String action) {
        if (!isSuperAdmin()) {
            throw new UnauthorizedAccessException(
                "SUPER_ADMIN required to " + action + ".");
        }
    }

    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority granted : auth.getAuthorities()) {
            if (SUPER_ADMIN_AUTHORITY.equalsIgnoreCase(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private SmartPhraseResponseDTO toDto(SmartPhrase phrase) {
        return SmartPhraseResponseDTO.builder()
            .id(phrase.getId())
            .trigger(phrase.getTrigger())
            .title(phrase.getTitle())
            .expansion(phrase.getExpansion())
            .scope(phrase.getScope())
            .hospitalId(phrase.getHospital() != null ? phrase.getHospital().getId() : null)
            .ownerUserId(phrase.getOwner() != null ? phrase.getOwner().getId() : null)
            .specialty(phrase.getSpecialty())
            .usageCount(phrase.getUsageCount())
            .lastUsedAt(phrase.getLastUsedAt())
            .build();
    }
}
