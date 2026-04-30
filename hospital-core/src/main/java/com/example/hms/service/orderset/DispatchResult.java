package com.example.hms.service.orderset;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of dispatching a single order-set item. Either a created
 * order id keyed by type, or a skipped reason — never both, never
 * null. Tests pattern-match on {@link #type()} or {@link #isCreated()}.
 */
public final class DispatchResult {

    public enum Type { MEDICATION, LAB, IMAGING, SKIPPED }

    private final Type type;
    private final UUID createdId;
    private final String skipReason;
    private final List<CdsCard> cdsAdvisories;

    private DispatchResult(Type type, UUID createdId, String skipReason, List<CdsCard> cdsAdvisories) {
        this.type = type;
        this.createdId = createdId;
        this.skipReason = skipReason;
        this.cdsAdvisories = cdsAdvisories == null ? List.of() : List.copyOf(cdsAdvisories);
    }

    public static DispatchResult medication(UUID id, List<CdsCard> cdsAdvisories) {
        return new DispatchResult(Type.MEDICATION, id, null, cdsAdvisories);
    }

    public static DispatchResult lab(UUID id) {
        return new DispatchResult(Type.LAB, id, null, null);
    }

    public static DispatchResult imaging(UUID id) {
        return new DispatchResult(Type.IMAGING, id, null, null);
    }

    public static DispatchResult skipped(String reason) {
        return new DispatchResult(Type.SKIPPED, null, reason, null);
    }

    public Type type() { return type; }
    public UUID createdId() { return createdId; }
    public String skipReason() { return skipReason; }
    public List<CdsCard> cdsAdvisories() { return cdsAdvisories; }
    public boolean isCreated() { return type != Type.SKIPPED; }
}
