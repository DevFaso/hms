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
import com.example.hms.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import java.util.UUID;

@Service
@Slf4j
public class SmartPhraseServiceImpl implements SmartPhraseService {

    private final SmartPhraseRepository repository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public SmartPhraseServiceImpl(SmartPhraseRepository repository,
                                  HospitalRepository hospitalRepository,
                                  UserRepository userRepository,
                                  Clock clock) {
        this.repository = repository;
        this.hospitalRepository = hospitalRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SmartPhraseResponseDTO create(SmartPhraseRequestDTO request) {
        validateRequest(request);
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
            .orElseThrow(() -> new ResourceNotFoundException("SmartPhrase not found: " + id));
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
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("SmartPhrase not found: " + id);
        }
        repository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public SmartPhraseResponseDTO get(UUID id) {
        return repository.findById(id)
            .map(this::toDto)
            .orElseThrow(() -> new ResourceNotFoundException("SmartPhrase not found: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SmartPhraseResponseDTO> listGlobal(Pageable pageable) {
        return repository.findByScope(SmartPhraseScope.GLOBAL, pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SmartPhraseResponseDTO> autocomplete(String rawPrefix, UUID hospitalId) {
        String prefix = rawPrefix == null ? "" : rawPrefix.trim().toLowerCase();
        if (prefix.isEmpty() || prefix.charAt(0) != '.') {
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
            throw new ResourceNotFoundException("SmartPhrase not found: " + id);
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
            case USER -> repository
                .findFirstByTriggerIgnoreCaseAndScopeAndHospital_IdAndOwner_Id(
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

    @SuppressWarnings("unused")
    private void requireAuthenticated() {
        if (SecurityUtils.getCurrentUsername() == null) {
            throw new UnauthorizedAccessException("No authenticated user in security context.");
        }
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
