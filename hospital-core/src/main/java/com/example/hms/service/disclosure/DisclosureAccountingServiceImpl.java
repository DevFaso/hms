package com.example.hms.service.disclosure;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DisclosureCategory;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.DisclosureAccountingDTO;
import com.example.hms.repository.AuditEventLogRepository;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** See {@link DisclosureAccountingService}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisclosureAccountingServiceImpl implements DisclosureAccountingService {

    private final AuditEventLogRepository auditRepository;

    @Override
    @Transactional(readOnly = true)
    public DisclosureAccountingDTO getAccounting(UUID patientId, LocalDateTime from,
                                                 LocalDateTime to, Pageable pageable) {
        Set<AuditEventType> accountable = DisclosureCategory.accountableEventTypes();
        Page<AccessLogEntryDTO> entries = queryEntries(patientId, accountable, from, to, pageable);

        Map<DisclosureCategory, Long> counts = countByCategory(patientId, accountable, from, to);

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long external = counts.entrySet().stream()
            .filter(e -> e.getKey().isExternalDisclosure())
            .mapToLong(Map.Entry::getValue)
            .sum();

        return DisclosureAccountingDTO.builder()
            .from(from)
            .to(to)
            .countsByCategory(counts)
            .totalEvents(total)
            .externalDisclosures(external)
            .entries(entries.getContent())
            .totalPages(entries.getTotalPages())
            .page(entries.getNumber())
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AccessLogEntryDTO> getEntries(UUID patientId, LocalDateTime from,
                                              LocalDateTime to, Pageable pageable) {
        return queryEntries(patientId, DisclosureCategory.accountableEventTypes(), from, to, pageable);
    }

    private Page<AccessLogEntryDTO> queryEntries(UUID patientId, Set<AuditEventType> accountable,
                                                 LocalDateTime from, LocalDateTime to,
                                                 Pageable pageable) {
        return auditRepository
            .findDisclosuresForPatient(patientId, accountable, from, to, pageable)
            .map(this::toEntry);
    }

    /**
     * Per-category totals across the whole window.
     *
     * <p>Grouped in the database on {@code (eventType, entityType)} and
     * folded to categories here, because the fold is not one-to-one:
     * {@code PATIENT_ACCESS} lands in {@code TREATMENT_ACCESS} or
     * {@code INSURANCE} depending on the entity type, and two group rows can
     * therefore collapse into one category. Summing rather than assigning is
     * what makes that safe.
     */
    private Map<DisclosureCategory, Long> countByCategory(UUID patientId,
                                                          Set<AuditEventType> accountable,
                                                          LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = auditRepository
            .countDisclosureCategoriesForPatient(patientId, accountable, from, to);

        Map<DisclosureCategory, Long> counts = new EnumMap<>(DisclosureCategory.class);
        for (Object[] row : rows) {
            AuditEventType eventType = (AuditEventType) row[0];
            String entityType = (String) row[1];
            long count = ((Number) row[2]).longValue();

            DisclosureCategory category = DisclosureCategory.classify(eventType, entityType);
            if (category == null) {
                // The query already filters to accountable types, so this is
                // only reachable if accountableEventTypes() and classify()
                // have drifted apart. Skip rather than guess a category, and
                // say so — DisclosureCategoryTest pins them together, so this
                // firing means that test stopped being true.
                log.warn("[DISCLOSURE] {} / {} is accountable but unclassified — dropped from counts",
                    eventType, entityType);
                continue;
            }
            counts.merge(category, count, Long::sum);
        }
        return counts;
    }

    private AccessLogEntryDTO toEntry(AuditEventLog event) {
        DisclosureCategory category =
            DisclosureCategory.classify(event.getEventType(), event.getEntityType());

        return AccessLogEntryDTO.builder()
            .id(event.getId())
            .actor(event.getUserName())
            .actorRole(event.getRoleName())
            .hospitalName(event.getHospitalName())
            .eventType(event.getEventType() != null ? event.getEventType().name() : null)
            .entityType(event.getEntityType())
            .resourceId(event.getResourceId())
            .description(event.getEventDescription())
            .status(event.getStatus() != null ? event.getStatus().name() : null)
            .timestamp(event.getEventTimestamp())
            .category(category)
            .externalDisclosure(category != null && category.isExternalDisclosure())
            .build();
    }
}
