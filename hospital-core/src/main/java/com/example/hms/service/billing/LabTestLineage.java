package com.example.hms.service.billing;

import com.example.hms.model.LabTestDefinition;
import com.example.hms.repository.LabTestDefinitionRepository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D2700 (r65): a lab test is revised by a new version of its definition (a new id, {@code supersedes_definition_id}
 * naming the version it replaces). A price-list link that named one version stopped pricing the test at the next
 * revision, in silence. The link's stable identity is the lineage's FIRST version: links are stored on it, and a
 * charge looks the ordered version's whole ancestry up. Walks are bounded and never loop.
 */
public final class LabTestLineage {

    /** No lineage is anywhere near this long; the bound only stops a corrupt cycle. */
    static final int MAX_DEPTH = 50;

    private LabTestLineage() {
    }

    /** The ordered version first, then each version it replaced, the lineage's first version last. */
    public static List<UUID> ancestry(LabTestDefinition definition, LabTestDefinitionRepository repository) {
        List<UUID> ids = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        LabTestDefinition current = definition;
        while (current != null && current.getId() != null && seen.add(current.getId()) && ids.size() < MAX_DEPTH) {
            ids.add(current.getId());
            UUID parent = current.getSupersedesDefinitionId();
            if (parent == null || repository == null) {
                break;
            }
            Optional<LabTestDefinition> found = repository.findById(parent);
            current = found.orElse(null);
        }
        return ids;
    }

    /** The lineage's first version: the stable key a price-list link is stored on. */
    public static UUID rootId(LabTestDefinition definition, LabTestDefinitionRepository repository) {
        List<UUID> ids = ancestry(definition, repository);
        return ids.isEmpty() ? null : ids.get(ids.size() - 1);
    }

    /**
     * Whether any version of the lineage starting at {@code rootId} is still active (orderable). A link whose whole
     * lineage is retired prices nothing any more; the price list flags it.
     */
    public static boolean hasActiveVersion(UUID rootId, LabTestDefinitionRepository repository) {
        if (rootId == null || repository == null) {
            return false;
        }
        Deque<UUID> queue = new ArrayDeque<>();
        Set<UUID> seen = new HashSet<>();
        queue.add(rootId);
        while (!queue.isEmpty() && seen.size() < MAX_DEPTH) {
            UUID id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            Optional<LabTestDefinition> definition = repository.findById(id);
            if (definition.isPresent() && definition.get().isActive()) {
                return true;
            }
            List<LabTestDefinition> next = repository.findBySupersedesDefinitionId(id);
            if (next != null) {
                next.forEach(d -> queue.add(d.getId()));
            }
        }
        return false;
    }
}
