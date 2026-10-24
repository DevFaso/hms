package com.example.hms.service.lab;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * What a reader outside the laboratory may see of a patient's stored lab
 * results (QA D708/D709, re-test D740/D741).
 *
 * <p>Every clinical, patient, FHIR and export read path goes through this
 * class; {@code ClinicalLabResultReadPathGuardTest} lists them and fails when
 * a new one reads results without it. Laboratory worklists, release queues
 * and the write side do not: there every row is the record.
 *
 * <ul>
 *   <li><strong>Unreleased values are not results yet.</strong> A row may be
 *       listed as pending, but its value, unit and flag are never shown
 *       ({@link #showsValue}). Export paths drop unreleased rows entirely
 *       ({@link #releasedAndCurrent}).</li>
 *   <li><strong>A released correction replaces what it corrects.</strong> A
 *       row is superseded when its back-link is set, or when a RELEASED row in
 *       the same read names it in {@code supersedesResultId} (rows corrected
 *       before the back-link was written everywhere).</li>
 *   <li><strong>Legacy duplicates.</strong> Before one result per order was
 *       enforced, a hand-entered order could carry several rows with no link
 *       between them. Per order, only the newest released hand-entered row is
 *       current; with none released, the newest pending one. Analyzer rows are
 *       judged by {@link SupersededLabResults} instead, because a timed series
 *       on one order is several real results.</li>
 * </ul>
 */
public final class ClinicalLabResultView {

    private ClinicalLabResultView() {
    }

    /** Whether a reader outside the laboratory may see this row's value, unit and flag. */
    public static boolean showsValue(LabResult row) {
        return row != null && row.isReleased();
    }

    /**
     * The rows that are current for a clinical reader, in the order given:
     * superseded rows and legacy duplicates removed. Unreleased rows stay, to
     * be listed as pending without their value.
     */
    public static List<LabResult> current(Collection<LabResult> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<LabResult> live = rows.stream().filter(Objects::nonNull).toList();
        Set<UUID> hidden = new HashSet<>(supersededIds(live));
        hidden.addAll(SupersededLabResults.supersededRowIds(live, live));
        hidden.addAll(legacyDuplicateIds(live, hidden));
        List<LabResult> out = new ArrayList<>(live.size());
        for (LabResult row : live) {
            if (row.getId() == null || !hidden.contains(row.getId())) {
                out.add(row);
            }
        }
        return out;
    }

    /** {@link #current}, less every unreleased row: for exports and FHIR, where a pending row has no meaning. */
    public static List<LabResult> releasedAndCurrent(Collection<LabResult> rows) {
        return current(rows).stream().filter(LabResult::isReleased).toList();
    }

    /**
     * Whether one row may be served to a reader outside the laboratory, judged
     * against the other rows of its order. A row read alone cannot be judged:
     * a legacy duplicate carries no link at all, and an older correction may
     * name its predecessor only through the newer row's forward link (QA D741).
     */
    public static boolean isReleasedAndCurrent(LabResult row, Collection<LabResult> orderSiblings) {
        if (row == null || !row.isReleased()) {
            return false;
        }
        List<LabResult> all = new ArrayList<>();
        if (orderSiblings != null) {
            all.addAll(orderSiblings);
        }
        if (row.getId() == null || all.stream().noneMatch(r -> r != null && row.getId().equals(r.getId()))) {
            all.add(row);
        }
        return releasedAndCurrent(all).stream()
            .anyMatch(r -> r == row || (row.getId() != null && row.getId().equals(r.getId())));
    }

    /**
     * The rows of {@code page}, in order, that are released and current once
     * judged against every row of their orders; {@code siblingsOfOrders} loads
     * those rows for a set of order ids (QA D741).
     */
    public static List<LabResult> releasedAndCurrentAmongOrders(List<LabResult> page,
            Function<Collection<UUID>, List<LabResult>> siblingsOfOrders) {
        if (page == null || page.isEmpty()) {
            return List.of();
        }
        Set<UUID> orderIds = new HashSet<>();
        for (LabResult row : page) {
            UUID orderId = row == null ? null : orderIdOf(row);
            if (orderId != null) {
                orderIds.add(orderId);
            }
        }
        Map<UUID, LabResult> all = new java.util.LinkedHashMap<>();
        List<LabResult> unidentified = new ArrayList<>();
        List<LabResult> siblings = orderIds.isEmpty() ? List.of() : siblingsOfOrders.apply(orderIds);
        for (LabResult row : siblings == null ? List.<LabResult>of() : siblings) {
            if (row != null && row.getId() != null) {
                all.putIfAbsent(row.getId(), row);
            }
        }
        for (LabResult row : page) {
            if (row == null) {
                continue;
            }
            if (row.getId() == null) {
                unidentified.add(row);
            } else {
                all.putIfAbsent(row.getId(), row);
            }
        }
        List<LabResult> judged = new ArrayList<>(all.values());
        judged.addAll(unidentified);
        Set<UUID> keptIds = new HashSet<>();
        Set<LabResult> keptRows = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (LabResult r : releasedAndCurrent(judged)) {
            keptRows.add(r);
            if (r.getId() != null) {
                keptIds.add(r.getId());
            }
        }
        return page.stream()
            .filter(Objects::nonNull)
            .filter(r -> r.getId() == null ? keptRows.contains(r) : keptIds.contains(r.getId()))
            .toList();
    }

    private static Set<UUID> supersededIds(List<LabResult> rows) {
        Set<UUID> ids = new HashSet<>();
        for (LabResult row : rows) {
            if (row.getId() != null && row.getSupersededByResultId() != null) {
                ids.add(row.getId());
            }
            if (row.isReleased() && row.getSupersedesResultId() != null) {
                ids.add(row.getSupersedesResultId());
            }
        }
        return ids;
    }

    private static Set<UUID> legacyDuplicateIds(List<LabResult> rows, Set<UUID> alreadyHidden) {
        Map<UUID, List<LabResult>> byOrder = new HashMap<>();
        for (LabResult row : rows) {
            UUID orderId = orderIdOf(row);
            if (orderId == null || row.getId() == null || alreadyHidden.contains(row.getId())
                || SupersededLabResults.cameFromAnAnalyzer(row)) {
                continue;
            }
            byOrder.computeIfAbsent(orderId, k -> new ArrayList<>()).add(row);
        }
        Set<UUID> ids = new HashSet<>();
        for (List<LabResult> group : byOrder.values()) {
            if (group.size() < 2) {
                continue;
            }
            boolean anyReleased = group.stream().anyMatch(LabResult::isReleased);
            LabResult keep = group.stream()
                .filter(r -> !anyReleased || r.isReleased())
                .max(NEWEST)
                .orElse(null);
            for (LabResult row : group) {
                if (row != keep) {
                    ids.add(row.getId());
                }
            }
        }
        return ids;
    }

    private static UUID orderIdOf(LabResult row) {
        LabOrder order = row.getLabOrder();
        return order == null ? null : order.getId();
    }

    private static final Comparator<LabResult> NEWEST =
        Comparator.comparing(LabResult::getResultDate, ClinicalLabResultView::compare)
            .thenComparing(LabResult::getCreatedAt, ClinicalLabResultView::compare)
            .thenComparing(LabResult::getId);

    private static int compare(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right == null ? 0 : -1;
        }
        return right == null ? 1 : left.compareTo(right);
    }
}
