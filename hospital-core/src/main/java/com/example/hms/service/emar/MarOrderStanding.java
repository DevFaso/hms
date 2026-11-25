package com.example.hms.service.emar;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.Prescription;

/**
 * QA D1392: whether a prescription's own status still allows a dose to be
 * checked or recorded. A cancelled order took a five-rights check and a GIVEN
 * dose, morphine included, because the eMAR only ever asked whether the stay
 * had ended. Every status of {@link PrescriptionStatus} is answered here, so a
 * new one has to be decided ({@code MarOrderStandingTest} enumerates them):
 * <ul>
 *   <li><b>withdrawn</b> (CANCELLED, DISCONTINUED): refused. The prescriber
 *       took the order back, or its stay ended it.</li>
 *   <li><b>never signed</b> (DRAFT, PENDING_SIGNATURE): refused. It is not yet
 *       an order; the MAR does not list it either.</li>
 *   <li><b>every other status stands</b>, the supply ones included
 *       (DISPENSED, PARTIALLY_FILLED, PENDING_STOCK, PENDING_CLARIFICATION, the
 *       partner statuses): they say where the medicine is, not that the
 *       prescriber stopped it. A ward order the pharmacy has just dispensed is
 *       exactly the one a nurse then gives, from a panel opened before the
 *       hand-over, so these keep working as they did. HELD and REFUSED are
 *       outcomes of a dose, not of an order: there is no held order to protect.</li>
 * </ul>
 * A 409: the order changed under the nurse's panel, which re-reads the list.
 */
public final class MarOrderStanding {

    private MarOrderStanding() {
    }

    /**
     * QA D3941: whether an order in this status stands, the one rule the ward MAR lists by
     * ({@link MarWardReader#ON_MAR_STATUSES}) and every dose is recorded by ({@link #requireStands}):
     * signed and not withdrawn. The pharmacy's supply statuses (DISPENSED, PARTIALLY_FILLED, PENDING_STOCK,
     * the partner statuses...) all stand: a ward order the pharmacy supplied is still to be given, and the
     * row says how much was supplied ({@link MarSupply}), it does not leave the list.
     */
    public static boolean stands(PrescriptionStatus status) {
        return status != null && !status.isWithdrawn()
            && status != PrescriptionStatus.DRAFT && status != PrescriptionStatus.PENDING_SIGNATURE;
    }

    public static void requireStands(Prescription rx) {
        PrescriptionStatus status = rx == null ? null : rx.getStatus();
        if (status == null) {
            return;
        }
        if (status.isWithdrawn()) {
            throw new ConflictException("nurse.mar.orderWithdrawn");
        }
        if (status == PrescriptionStatus.DRAFT || status == PrescriptionStatus.PENDING_SIGNATURE) {
            throw new ConflictException("nurse.mar.orderNotSigned");
        }
    }
}
