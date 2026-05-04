package com.example.hms.service.impl;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.UnauthorizedException;
import com.example.hms.model.platform.AuditSavedSearch;
import com.example.hms.payload.dto.superadmin.AuditSavedSearchRequestDTO;
import com.example.hms.payload.dto.superadmin.AuditSavedSearchResponseDTO;
import com.example.hms.repository.platform.AuditSavedSearchRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditSavedSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AuditSavedSearchServiceImpl implements AuditSavedSearchService {

    private final AuditSavedSearchRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<AuditSavedSearchResponseDTO> listVisible() {
        String owner = currentOwnerOrThrow();
        return repository.findOwnedAndShared(owner).stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public AuditSavedSearchResponseDTO create(AuditSavedSearchRequestDTO request) {
        String owner = currentOwnerOrThrow();
        validate(request);

        Optional<AuditSavedSearch> existing = repository
            .findByOwnerUsernameAndName(owner, request.getName().trim());
        if (existing.isPresent()) {
            throw new BusinessRuleException(
                "A saved search with that name already exists for this operator: "
                    + request.getName());
        }

        AuditSavedSearch entity = AuditSavedSearch.builder()
            .ownerUsername(owner)
            .name(request.getName().trim())
            .filterJson(request.getFilterJson())
            .shared(request.isShared())
            .build();
        entity = repository.save(entity);
        return toResponse(entity);
    }

    @Override
    public AuditSavedSearchResponseDTO update(UUID id, AuditSavedSearchRequestDTO request) {
        String owner = currentOwnerOrThrow();
        validate(request);

        AuditSavedSearch entity = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Saved search not found: " + id));

        if (!owner.equals(entity.getOwnerUsername())) {
            // Sharing is read-only across owners — only the original owner
            // can mutate (rename, change filter, toggle shared).
            throw new UnauthorizedException("Only the owner can modify this saved search.");
        }

        // If renaming, ensure the new name doesn't collide with another
        // search owned by the same operator.
        String newName = request.getName().trim();
        if (!newName.equals(entity.getName())) {
            repository.findByOwnerUsernameAndName(owner, newName).ifPresent(other -> {
                if (!other.getId().equals(id)) {
                    throw new BusinessRuleException(
                        "A saved search with that name already exists for this operator: "
                            + newName);
                }
            });
            entity.setName(newName);
        }

        entity.setFilterJson(request.getFilterJson());
        entity.setShared(request.isShared());
        entity = repository.save(entity);
        return toResponse(entity);
    }

    @Override
    public void delete(UUID id) {
        String owner = currentOwnerOrThrow();
        AuditSavedSearch entity = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Saved search not found: " + id));
        if (!owner.equals(entity.getOwnerUsername())) {
            throw new UnauthorizedException("Only the owner can delete this saved search.");
        }
        repository.delete(entity);
    }

    // ── helpers ───────────────────────────────────────────────────────

    private String currentOwnerOrThrow() {
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        String username = ctx.getPrincipalUsername();
        if (username == null || username.isBlank()) {
            throw new UnauthorizedException("Saved-search calls require an authenticated principal.");
        }
        return username;
    }

    private void validate(AuditSavedSearchRequestDTO request) {
        if (request == null) {
            throw new BusinessRuleException("Saved-search payload is required.");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessRuleException("Saved-search name is required.");
        }
        if (request.getFilterJson() == null || request.getFilterJson().isBlank()) {
            throw new BusinessRuleException("Saved-search filterJson is required.");
        }
    }

    private AuditSavedSearchResponseDTO toResponse(AuditSavedSearch entity) {
        return AuditSavedSearchResponseDTO.builder()
            .id(entity.getId())
            .ownerUsername(entity.getOwnerUsername())
            .name(entity.getName())
            .filterJson(entity.getFilterJson())
            .shared(entity.isShared())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }
}
