package com.example.hms.service.recordaccess;

import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Prescription;

import java.util.List;
import java.util.UUID;

/**
 * QA D509 / D623 / D652 - the one rule every cross-hospital patient read
 * applies to a row that was never sent: a DRAFT (or an order still waiting for
 * its signature) is the writing hospital's work in progress, and exists only
 * for that hospital.
 *
 * <p>A patient list that reaches across hospitals (E9, {@code readableHospitalIds})
 * and the by-id read of the same record must both ask this question, or the
 * list shows a row the by-id read then answers 404 for (the referral list did,
 * then the prescription list). {@code CrossHospitalListByIdAgreementTest}
 * holds every such list to it.
 */
public final class NeverSentRule {

    /** A prescription in one of these was never issued: unsigned work in progress (QA D101, D152, D652). */
    public static final List<PrescriptionStatus> NEVER_SENT_PRESCRIPTION =
        List.of(PrescriptionStatus.DRAFT, PrescriptionStatus.PENDING_SIGNATURE);

    private NeverSentRule() {
    }

    public static boolean sent(PrescriptionStatus status) {
        return status != PrescriptionStatus.DRAFT && status != PrescriptionStatus.PENDING_SIGNATURE;
    }

    public static boolean sent(ImagingOrderStatus status) {
        return status != ImagingOrderStatus.DRAFT;
    }

    /**
     * The prescriptions of a cross-hospital read that may be shown at
     * {@code activeHospitalId}: every chart section, timeline and summary that
     * reads prescriptions across {@code readableHospitalIds} passes its rows
     * through here, so none of them shows what the by-id read refuses.
     */
    public static List<Prescription> prescriptionsReadableHere(List<Prescription> rows, UUID activeHospitalId) {
        return rows.stream()
            .filter(p -> readableHere(CrossHospitalReachRecorder.hospitalIdOf(p.getHospital()), activeHospitalId,
                sent(p.getStatus())))
            .toList();
    }

    /** {@link #prescriptionsReadableHere} for imaging orders: another hospital's DRAFT is left out. */
    public static List<ImagingOrder> imagingOrdersReadableHere(List<ImagingOrder> rows, UUID activeHospitalId) {
        return rows.stream()
            .filter(o -> readableHere(CrossHospitalReachRecorder.hospitalIdOf(o.getHospital()), activeHospitalId,
                sent(o.getStatus())))
            .toList();
    }

    /**
     * May a row written at {@code rowHospitalId} be read at {@code activeHospitalId}?
     * Always at its own hospital (and in global view, {@code activeHospitalId == null});
     * elsewhere only once it was sent. The readable-hospitals gate is applied
     * separately, by the caller; a row with no hospital is not another
     * hospital's, and is left to that gate.
     */
    public static boolean readableHere(UUID rowHospitalId, UUID activeHospitalId, boolean sent) {
        return activeHospitalId == null || rowHospitalId == null || activeHospitalId.equals(rowHospitalId) || sent;
    }
}
