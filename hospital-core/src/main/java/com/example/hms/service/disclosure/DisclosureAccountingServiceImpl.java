package com.example.hms.service.disclosure;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DisclosureCategory;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.DisclosureAccountingDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.utility.RoleNames;

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

    @Override
    @Transactional(readOnly = true)
    public DisclosureAccountingDTO getAccountingAsPatientSees(UUID patientId, LocalDateTime from,
                                                              LocalDateTime to, Pageable pageable) {
        Set<AuditEventType> accountable = DisclosureCategory.accountableEventTypes();
        Map<DisclosureCategory, Long> counts = countByCategory(patientId, accountable, from, to);
        Page<AccessLogEntryDTO> folded = getEntriesAsPatientSees(patientId, from, to, pageable);
        return DisclosureAccountingDTO.builder()
            .from(from)
            .to(to)
            .countsByCategory(counts)
            .totalEvents(counts.values().stream().mapToLong(Long::longValue).sum())
            .externalDisclosures(counts.entrySet().stream()
                .filter(e -> e.getKey().isExternalDisclosure())
                .mapToLong(Map.Entry::getValue)
                .sum())
            .entries(folded.getContent())
            .totalPages(folded.getTotalPages())
            .page(folded.getNumber())
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AccessLogEntryDTO> getEntriesAsPatientSees(UUID patientId, LocalDateTime from,
                                                           LocalDateTime to, Pageable pageable) {
        // Folded over the most recent rows first, then paged: a repeat across a
        // page boundary still shows once, and the pager counts the lines shown.
        org.springframework.data.domain.Sort sort = pageable.getSort().isSorted()
            ? pageable.getSort()
            : org.springframework.data.domain.Sort.unsorted();
        Page<AccessLogEntryDTO> window = getEntries(patientId, from, to,
            org.springframework.data.domain.PageRequest.of(0, PATIENT_FOLD_WINDOW, sort));
        List<AccessLogEntryDTO> lines = new java.util.ArrayList<>(foldRepeats(window.getContent()));
        // A record read more than PATIENT_FOLD_WINDOW times: the older rows follow, as they are.
        long older = Math.max(0, window.getTotalElements() - window.getContent().size());
        if (pageable.isUnpaged()) {
            if (older > 0) {
                lines.addAll(getEntries(patientId, from, to,
                    org.springframework.data.domain.PageRequest.of(1, PATIENT_FOLD_WINDOW, sort)).getContent());
            }
            return new org.springframework.data.domain.PageImpl<>(lines);
        }
        long total = lines.size() + older;
        long start = (long) pageable.getPageNumber() * pageable.getPageSize();
        List<AccessLogEntryDTO> content = new java.util.ArrayList<>();
        for (long i = start; i < Math.min(start + pageable.getPageSize(), total); i++) {
            if (i < lines.size()) {
                content.add(lines.get((int) i));
            } else {
                long olderIndex = PATIENT_FOLD_WINDOW + (i - lines.size());
                getEntries(patientId, from, to, org.springframework.data.domain.PageRequest.of(
                        (int) olderIndex, 1, sort)).getContent().forEach(content::add);
            }
        }
        return new org.springframework.data.domain.PageImpl<>(content, pageable, total);
    }

    /** QA D979: the most recent audit rows folded for the patient's list (older ones are in the staff accounting). */
    private static final int PATIENT_FOLD_WINDOW = 2000;

    /** QA D979: the window in which the same person reading the same way again shows once. */
    private static final java.time.Duration REPEAT_WINDOW = java.time.Duration.ofMinutes(30);

    /** QA D979: the categories a repeat of folds into one line; every emergency, copy or insurer row stays. */
    private static final Set<DisclosureCategory> FOLDABLE =
        java.util.EnumSet.of(DisclosureCategory.TREATMENT_ACCESS, DisclosureCategory.SHARED_WITH_PROVIDER);

    /**
     * QA D979: Joséphine Hien appeared five times in 40 seconds as "shared
     * with a provider" (every chart and timeline read writes a RECORD_SHARE),
     * although the page says repeat views during one visit show once. The
     * audit keeps every row and the counts still add them all up; only the
     * list the patient reads folds a repeat, by the same person, of the same
     * kind, from the same hospital, within 30 minutes of the line kept.
     */
    static List<AccessLogEntryDTO> foldRepeats(List<AccessLogEntryDTO> entries) {
        List<AccessLogEntryDTO> kept = new java.util.ArrayList<>(entries.size());
        Map<String, LocalDateTime> lastKept = new java.util.HashMap<>();
        for (AccessLogEntryDTO entry : entries) {
            if (entry.getCategory() == null || !FOLDABLE.contains(entry.getCategory()) || entry.getTimestamp() == null) {
                kept.add(entry);
                continue;
            }
            String key = entry.getCategory() + "|" + entry.getActor() + "|" + entry.getHospitalName()
                + "|" + entry.getDescription();
            LocalDateTime previous = lastKept.get(key);
            if (previous != null
                    && java.time.Duration.between(entry.getTimestamp(), previous).abs().compareTo(REPEAT_WINDOW) <= 0) {
                continue;
            }
            lastKept.put(key, entry.getTimestamp());
            kept.add(entry);
        }
        return kept;
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
            // One choke point for both audit surfaces: legacy rows stored
            // "ROLE_X" or the "Unknown Role" sentence, and no write reaches them.
            .actorRole(RoleNames.bareRole(event.getRoleName()))
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
