package com.example.hms.service;

import com.example.hms.enums.ReferenceCatalogStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.reference.ReferenceCatalog;
import com.example.hms.model.reference.ReferenceCatalogEntry;
import com.example.hms.payload.dto.reference.CatalogImportResponseDTO;
import com.example.hms.payload.dto.reference.CreateReferenceCatalogRequestDTO;
import com.example.hms.payload.dto.reference.ReferenceCatalogResponseDTO;
import com.example.hms.payload.dto.reference.SchedulePublishRequestDTO;
import com.example.hms.repository.reference.ReferenceCatalogEntryRepository;
import com.example.hms.repository.reference.ReferenceCatalogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceCatalogServiceImpl implements ReferenceCatalogService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ReferenceCatalogRepository catalogRepository;
    private final ReferenceCatalogEntryRepository entryRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ReferenceCatalogResponseDTO> listCatalogs() {
        return catalogRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
            .stream()
            .map(this::toDto)
            .toList();
    }

    @Override
    @Transactional
    public ReferenceCatalogResponseDTO createCatalog(CreateReferenceCatalogRequestDTO requestDTO) {
        String code = normalizeCode(requestDTO.getCode());
        if (code == null) {
            throw new BusinessException("Catalog code cannot be blank");
        }
        catalogRepository.findByCodeIgnoreCase(code)
            .ifPresent(existing -> {
                throw new BusinessException("Catalog with code '" + code + "' already exists");
            });

        ReferenceCatalog catalog = ReferenceCatalog.builder()
            .code(code)
            .name(requestDTO.getName().trim())
            .description(StringUtils.hasText(requestDTO.getDescription())
                ? requestDTO.getDescription().trim()
                : null)
            .status(ReferenceCatalogStatus.DRAFT)
            .entryCount(0)
            .build();

        ReferenceCatalog saved = catalogRepository.save(catalog);
        log.info("[catalog:create] code={} id={}", code, saved.getId());
        return toDto(saved);
    }

    @Override
    @Transactional
    public CatalogImportResponseDTO importCatalog(UUID catalogId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("CSV file is required for import");
        }

        ReferenceCatalog catalog = catalogRepository.findById(catalogId)
            .orElseThrow(() -> new ResourceNotFoundException("Catalog not found: " + catalogId));

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT
                 .withFirstRecordAsHeader()
                 .withIgnoreEmptyLines(true)
                 .withTrim(true)
                 .parse(reader)) {
            CatalogImportStats stats = processCsvRecords(parser, catalog);
            long count = entryRepository.countByCatalogId(catalog.getId());
            catalog.setEntryCount((int) Math.min(count, Integer.MAX_VALUE));
            catalog.setLastImportedAt(LocalDateTime.now());
            catalog.setStatus(ReferenceCatalogStatus.DRAFT);

            ReferenceCatalog saved = catalogRepository.save(catalog);
            log.info("[catalog:import] catalog={} processed={} created={} updated={} skipped={} entries={} file={}",
                saved.getCode(), stats.processed(), stats.created(), stats.updated(), stats.skipped(), count,
                file.getOriginalFilename());

            return CatalogImportResponseDTO.builder()
                .catalog(toDto(saved))
                .processed(stats.processed())
                .created(stats.created())
                .updated(stats.updated())
                .skipped(stats.skipped())
                .build();
        } catch (IOException ex) {
            throw new BusinessException("Failed to parse catalog CSV: " + ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional
    public ReferenceCatalogResponseDTO schedulePublish(UUID catalogId, SchedulePublishRequestDTO requestDTO) {
        ReferenceCatalog catalog = catalogRepository.findById(catalogId)
            .orElseThrow(() -> new ResourceNotFoundException("Catalog not found: " + catalogId));

        LocalDateTime publishAt = requestDTO.getPublishAt();
        LocalDateTime now = LocalDateTime.now();
        if (publishAt.isAfter(now)) {
            catalog.setScheduledPublishAt(publishAt);
            catalog.setStatus(ReferenceCatalogStatus.SCHEDULED);
            catalog.setPublishedAt(null);
        } else {
            catalog.setScheduledPublishAt(null);
            catalog.setPublishedAt(now);
            catalog.setStatus(ReferenceCatalogStatus.ACTIVE);
        }
        ReferenceCatalog saved = catalogRepository.save(catalog);
        log.info("[catalog:schedule] catalog={} status={} publishAt={}",
            saved.getCode(), saved.getStatus(), publishAt);
        return toDto(saved);
    }

    private ReferenceCatalogResponseDTO toDto(ReferenceCatalog catalog) {
        return ReferenceCatalogResponseDTO.builder()
            .id(catalog.getId())
            .code(catalog.getCode())
            .name(catalog.getName())
            .description(catalog.getDescription())
            .status(catalog.getStatus())
            .entryCount(catalog.getEntryCount())
            .publishedAt(catalog.getPublishedAt())
            .scheduledPublishAt(catalog.getScheduledPublishAt())
            .lastImportedAt(catalog.getLastImportedAt())
            .build();
    }

    private String normalizeCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        String normalized = trimmed.replaceAll("[^A-Za-z0-9]+", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_", "");
        normalized = normalized.replaceAll("_$", "");
        if (!StringUtils.hasText(normalized)) {
            normalized = trimmed.replaceAll("\\s+", "_");
        }
        return normalized.toUpperCase();
    }

    private String normalizeLabelFromCode(String code) {
        if (!StringUtils.hasText(code)) {
            return "Unnamed";
        }
        String lower = code.toLowerCase();
        String[] parts = lower.split("[_-]");
        var words = java.util.Arrays.stream(parts)
            .filter(StringUtils::hasText)
            .map(part -> {
                if (part.length() == 0) {
                    return part;
                }
                return Character.toUpperCase(part.charAt(0)) + part.substring(1);
            })
            .toList();
        return String.join(" ", words);
    }

    private String value(CSVRecord csvRow, String column) {
        if (csvRow.isMapped(column)) {
            String val = csvRow.get(column);
            return val != null ? val.trim() : null;
        }
        return null;
    }

    private Map<String, Object> parseMetadata(CSVRecord csvRow) {
        String raw = value(csvRow, "metadata");
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(raw, MAP_TYPE);
            return result != null ? result : Map.of();
        } catch (JsonProcessingException ex) {
            log.warn("[catalog:import] metadata parse error row={} reason={}", csvRow.getRecordNumber(), ex.getMessage());
            return Map.of();
        }
    }

    private boolean parseActiveFlag(CSVRecord csvRow) {
        String raw = value(csvRow, "active");
        if (!StringUtils.hasText(raw)) {
            return true;
        }
        return !raw.equalsIgnoreCase("false") && !raw.equalsIgnoreCase("0") && !raw.equalsIgnoreCase("no");
    }

    private CatalogImportStats processCsvRecords(CSVParser parser, ReferenceCatalog catalog) throws IOException {
        int processed = 0;
        int created = 0;
        int updated = 0;
        int skipped = 0;

        for (CSVRecord csvRow : parser) {
            CatalogImportStats rowStats = processCsvRow(catalog, csvRow);
            processed += rowStats.processed();
            created += rowStats.created();
            updated += rowStats.updated();
            skipped += rowStats.skipped();
        }
        return new CatalogImportStats(processed, created, updated, skipped);
    }

    private CatalogImportStats processCsvRow(ReferenceCatalog catalog, CSVRecord csvRow) {
        String rawCode = value(csvRow, "code");
        if (!StringUtils.hasText(rawCode)) {
            return CatalogImportStats.skippedAction();
        }

        String normalizedCode = normalizeCode(rawCode);
        if (normalizedCode == null) {
            return CatalogImportStats.skippedAction();
        }

        String label = value(csvRow, "label");
        if (!StringUtils.hasText(label)) {
            label = normalizeLabelFromCode(normalizedCode);
        }

        String description = value(csvRow, "description");
        Map<String, Object> metadata = parseMetadata(csvRow);
        if (metadata.isEmpty()) {
            metadata = null;
        }
        boolean active = parseActiveFlag(csvRow);

        return upsertCatalogEntry(catalog, normalizedCode, label, description, metadata, active);
    }

    private CatalogImportStats upsertCatalogEntry(ReferenceCatalog catalog, String code, String label, String description,
                                                 Map<String, Object> metadata, boolean active) {
        ReferenceCatalogEntry entry = entryRepository
            .findByCatalogIdAndCodeIgnoreCase(catalog.getId(), code)
            .orElse(null);

        if (entry == null) {
            ReferenceCatalogEntry created = ReferenceCatalogEntry.builder()
                .catalog(catalog)
                .code(code)
                .label(label)
                .description(description)
                .metadata(metadata)
                .active(active)
                .build();
            entryRepository.save(created);
            return CatalogImportStats.createdAction();
        }

        boolean changed = false;
        if (!Objects.equals(entry.getLabel(), label)) {
            entry.setLabel(label);
            changed = true;
        }
        if (!Objects.equals(entry.getDescription(), description)) {
            entry.setDescription(description);
            changed = true;
        }
        if (!Objects.equals(entry.getMetadata(), metadata)) {
            entry.setMetadata(metadata);
            changed = true;
        }
        if (entry.isActive() != active) {
            entry.setActive(active);
            changed = true;
        }

        if (changed) {
            entryRepository.save(entry);
            return CatalogImportStats.updatedAction();
        }
        return CatalogImportStats.skippedAction();
    }

    private record CatalogImportStats(int processed, int created, int updated, int skipped) {
        static CatalogImportStats createdAction() {
            return new CatalogImportStats(1, 1, 0, 0);
        }

        static CatalogImportStats updatedAction() {
            return new CatalogImportStats(1, 0, 1, 0);
        }

        static CatalogImportStats skippedAction() {
            return new CatalogImportStats(1, 0, 0, 1);
        }
    }
}
