package com.example.hms.service;

import com.example.hms.enums.PermissionMatrixAuditAction;
import com.example.hms.enums.PermissionMatrixEnvironment;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.PermissionMatrixAuditEvent;
import com.example.hms.model.PermissionMatrixSnapshot;
import com.example.hms.payload.dto.PermissionMatrixAuditEventRequestDTO;
import com.example.hms.payload.dto.PermissionMatrixAuditEventResponseDTO;
import com.example.hms.payload.dto.PermissionMatrixRowDTO;
import com.example.hms.payload.dto.PermissionMatrixSnapshotRequestDTO;
import com.example.hms.payload.dto.PermissionMatrixSnapshotResponseDTO;
import com.example.hms.repository.PermissionMatrixAuditEventRepository;
import com.example.hms.repository.PermissionMatrixSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionMatrixServiceImpl implements PermissionMatrixService {

    private static final TypeReference<List<PermissionMatrixRowDTO>> ROW_LIST_TYPE = new TypeReference<>() {
    };

    private final PermissionMatrixSnapshotRepository snapshotRepository;
    private final PermissionMatrixAuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public PermissionMatrixSnapshotResponseDTO publishSnapshot(PermissionMatrixSnapshotRequestDTO request, String initiatedBy, Locale locale) {
        Objects.requireNonNull(request, "Permission matrix request cannot be null");
        if (request.getRows() == null || request.getRows().isEmpty()) {
            throw new BusinessException("permission.matrix.rows.required");
        }

        String publisher = (initiatedBy != null && !initiatedBy.isBlank()) ? initiatedBy : "system";

        String matrixJson = writeRows(request.getRows());
        int nextVersion = snapshotRepository
            .findFirstByEnvironmentOrderByVersionNumberDesc(request.getEnvironment())
            .map(snapshot -> snapshot.getVersionNumber() + 1)
            .orElse(1);

        PermissionMatrixSnapshot snapshot = PermissionMatrixSnapshot.builder()
            .environment(request.getEnvironment())
            .sourceSnapshotId(request.getSourceSnapshotId())
            .versionNumber(nextVersion)
            .label(request.getLabel())
            .notes(request.getNotes())
            .createdBy(publisher)
            .createdAt(Instant.now())
            .matrixJson(matrixJson)
            .build();

        PermissionMatrixSnapshot saved = snapshotRepository.save(snapshot);

        auditEventRepository.save(PermissionMatrixAuditEvent.builder()
            .action(PermissionMatrixAuditAction.SNAPSHOT_PUBLISHED)
            .description(request.getLabel())
            .initiatedBy(publisher)
            .createdAt(Instant.now())
            .snapshot(saved)
            .metadataJson(request.getNotes())
            .matrixJson(matrixJson)
            .build());

        return mapSnapshot(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionMatrixSnapshotResponseDTO getLatestSnapshot(PermissionMatrixEnvironment environment, Locale locale) {
        PermissionMatrixSnapshot snapshot = snapshotRepository
            .findFirstByEnvironmentOrderByVersionNumberDesc(environment)
            .orElseThrow(() -> new ResourceNotFoundException("permission.matrix.snapshot.notFound"));
        return mapSnapshot(snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionMatrixSnapshotResponseDTO> listSnapshots(PermissionMatrixEnvironment environment, Locale locale) {
        List<PermissionMatrixSnapshot> snapshots;
        if (environment == null) {
            snapshots = snapshotRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        } else {
            snapshots = snapshotRepository.findByEnvironmentOrderByVersionNumberDesc(environment);
        }
        return snapshots.stream().map(this::mapSnapshot).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionMatrixSnapshotResponseDTO getSnapshot(UUID snapshotId, Locale locale) {
        PermissionMatrixSnapshot snapshot = snapshotRepository.findById(snapshotId)
            .orElseThrow(() -> new ResourceNotFoundException("permission.matrix.snapshot.notFound"));
        return mapSnapshot(snapshot);
    }

    @Override
    @Transactional
    public PermissionMatrixAuditEventResponseDTO recordAuditEvent(PermissionMatrixAuditEventRequestDTO request, String initiatedBy) {
        Objects.requireNonNull(request, "Permission matrix audit request cannot be null");

        String actor = (initiatedBy != null && !initiatedBy.isBlank()) ? initiatedBy : "system";

        PermissionMatrixAuditEvent.PermissionMatrixAuditEventBuilder builder = PermissionMatrixAuditEvent.builder()
            .action(request.getAction())
            .leftEnvironment(request.getLeftEnvironment())
            .rightEnvironment(request.getRightEnvironment())
            .description(request.getDescription())
            .initiatedBy(actor)
            .createdAt(Instant.now())
            .metadataJson(request.getMetadata());

        if (request.getSnapshotId() != null) {
            Optional<PermissionMatrixSnapshot> snapshot = snapshotRepository.findById(request.getSnapshotId());
            snapshot.ifPresent(builder::snapshot);
        }

        if (request.getMatrix() != null && !request.getMatrix().isEmpty()) {
            builder.matrixJson(writeRows(request.getMatrix()));
        }

        PermissionMatrixAuditEvent saved = auditEventRepository.save(builder.build());
        return mapAudit(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionMatrixAuditEventResponseDTO> listRecentAuditEvents(PermissionMatrixAuditAction actionFilter) {
        List<PermissionMatrixAuditEvent> events;
        if (actionFilter == null) {
            events = auditEventRepository.findTop50ByOrderByCreatedAtDesc();
        } else {
            events = auditEventRepository.findTop50ByActionOrderByCreatedAtDesc(actionFilter);
        }

        return events.stream()
            .map(this::mapAudit)
            .toList();
    }

    private PermissionMatrixSnapshotResponseDTO mapSnapshot(PermissionMatrixSnapshot snapshot) {
        return PermissionMatrixSnapshotResponseDTO.builder()
            .id(snapshot.getId())
            .sourceSnapshotId(snapshot.getSourceSnapshotId())
            .environment(snapshot.getEnvironment())
            .version(snapshot.getVersionNumber())
            .label(snapshot.getLabel())
            .notes(snapshot.getNotes())
            .createdBy(snapshot.getCreatedBy())
            .createdAt(snapshot.getCreatedAt())
            .rows(readRows(snapshot.getMatrixJson()))
            .build();
    }

    private PermissionMatrixAuditEventResponseDTO mapAudit(PermissionMatrixAuditEvent event) {
        return PermissionMatrixAuditEventResponseDTO.builder()
            .id(event.getId())
            .action(event.getAction())
            .leftEnvironment(event.getLeftEnvironment())
            .rightEnvironment(event.getRightEnvironment())
            .snapshotId(Optional.ofNullable(event.getSnapshot()).map(PermissionMatrixSnapshot::getId).orElse(null))
            .description(event.getDescription())
            .initiatedBy(event.getInitiatedBy())
            .createdAt(event.getCreatedAt())
            .metadata(event.getMetadataJson())
            .matrix(readRows(event.getMatrixJson()))
            .build();
    }

    private List<PermissionMatrixRowDTO> readRows(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, ROW_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize permission matrix", e);
            throw new BusinessException("permission.matrix.serialization.error");
        }
    }

    private String writeRows(List<PermissionMatrixRowDTO> rows) {
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize permission matrix", e);
            throw new BusinessException("permission.matrix.serialization.error");
        }
    }
}