package com.example.hms.service.support;

import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.model.ImagingReport;

import java.util.Comparator;
import java.util.List;

/**
 * QA D1924 / D1930: which version of a study's report is its conclusion,
 * wherever a conclusion is read (the chart card and timeline, the record PDF,
 * the after-visit summary).
 *
 * <p>In version order: the first signed original (FINAL) is the conclusion; a
 * later correction or amendment replaces it; an addendum never does. A FINAL
 * after the first one is an addendum signed before D1930 was fixed (it was
 * stored FINAL), so it never replaces the original either.
 */
public final class ImagingReportConclusion {

    /** Version order: the report version, then the creation time. */
    public static final Comparator<ImagingReport> VERSION_ORDER =
        Comparator.comparing((ImagingReport r) -> r.getReportVersion() != null ? r.getReportVersion() : 0)
            .thenComparing(ImagingReport::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));

    private ImagingReportConclusion() {
    }

    /** The conclusion among {@code shown}, which is in version order; null when it is empty. */
    public static ImagingReport of(List<ImagingReport> shown) {
        ImagingReport conclusion = null;
        for (ImagingReport r : shown) {
            if (conclusion == null || replacesTheConclusion(r.getReportStatus())) {
                conclusion = r;
            }
        }
        return conclusion;
    }

    /** How a shown version reads: a FINAL after the first FINAL of the study is the addendum it was. */
    public static String kindOf(ImagingReport r, List<ImagingReport> shown) {
        ImagingReportStatus status = r.getReportStatus();
        if (status == ImagingReportStatus.FINAL) {
            for (ImagingReport earlier : shown) {
                if (earlier == r) {
                    break;
                }
                if (earlier.getReportStatus() == ImagingReportStatus.FINAL) {
                    return ImagingReportStatus.ADDENDUM.name();
                }
            }
        }
        return status != null ? status.name() : null;
    }

    private static boolean replacesTheConclusion(ImagingReportStatus status) {
        return status == ImagingReportStatus.CORRECTED || status == ImagingReportStatus.AMENDED;
    }
}
