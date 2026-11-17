package com.example.hms.service.support;

import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingReportStatusHistory;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * QA D1924 / D1930: which version of a study's report is its conclusion,
 * wherever a conclusion is read (the chart card and timeline, the record PDF,
 * the after-visit summary).
 *
 * <p>In version order, the conclusion is the LATEST version that is not an
 * addendum: a later original read, correction or amendment replaces an
 * earlier one; an addendum adds to it and never replaces it. An obsolete
 * conclusion is never brought back.
 *
 * <p>What a version is comes from its provenance, not its position (Rex on
 * #1171). Before D1930 was fixed, signing stored every report FINAL; the kind
 * it was written as is still on its status history (the AUTHORED row, or any
 * row written before the signature), so a FINAL whose last pre-signature kind
 * is ADDENDUM is read as the addendum it was, and one that was a correction
 * stays a correction. A FINAL with no such history is an original read: as the
 * latest, it is the conclusion, and earlier ones are prior versions.
 */
public final class ImagingReportConclusion {

    /** Version order: the report version, then the creation time. */
    public static final Comparator<ImagingReport> VERSION_ORDER =
        Comparator.comparing((ImagingReport r) -> r.getReportVersion() != null ? r.getReportVersion() : 0)
            .thenComparing(ImagingReport::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));

    private static final Set<ImagingReportStatus> REVISIONS =
        EnumSet.of(ImagingReportStatus.ADDENDUM, ImagingReportStatus.CORRECTED, ImagingReportStatus.AMENDED);

    private ImagingReportConclusion() {
    }

    /** The conclusion among {@code shown}, which is in version order; null when it is empty. */
    public static ImagingReport of(List<ImagingReport> shown) {
        ImagingReport conclusion = null;
        for (ImagingReport r : shown) {
            if (conclusion == null || kind(r) != ImagingReportStatus.ADDENDUM) {
                conclusion = r;
            }
        }
        return conclusion;
    }

    /** How a shown version reads: its recorded kind (see {@link #kind}). */
    public static String kindOf(ImagingReport r) {
        ImagingReportStatus kind = kind(r);
        return kind != null ? kind.name() : null;
    }

    /**
     * The report's kind: its status, except a FINAL signed before D1930 was
     * fixed, which is the revision kind its history recorded before the
     * signature (ADDENDUM, CORRECTED or AMENDED), else FINAL.
     */
    public static ImagingReportStatus kind(ImagingReport r) {
        ImagingReportStatus status = r.getReportStatus();
        if (status != ImagingReportStatus.FINAL || r.getStatusHistory() == null) {
            return status;
        }
        return r.getStatusHistory().stream()
            .filter(Objects::nonNull)
            .filter(h -> h.getStatus() != null && h.getStatus() != ImagingReportStatus.FINAL)
            .filter(h -> r.getSignedAt() == null || h.getChangedAt() == null || !h.getChangedAt().isAfter(r.getSignedAt()))
            .max(Comparator.comparing(ImagingReportStatusHistory::getChangedAt,
                Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder())))
            .map(ImagingReportStatusHistory::getStatus)
            .filter(REVISIONS::contains)
            .orElse(ImagingReportStatus.FINAL);
    }
}
