package com.example.hms.enums;

import java.util.Set;

/**
 * ABO blood group, and the compatibility rules that go with it.
 *
 * <p>This enum is the safety core of the transfusion module — the analogue of
 * {@code FiveRightsVerificationService} for the eMAR. An ABO-incompatible red
 * cell transfusion is the classic never-event: it causes acute intravascular
 * haemolysis and it kills people. So compatibility is decided <b>server-side
 * and fail-closed</b>: a crossmatch a user marks "compatible" for a pair these
 * rules reject is refused outright, not warned about.
 *
 * <p><b>The direction of the rule flips with the product</b>, which is the part
 * that is easy to get wrong:
 *
 * <ul>
 *   <li><b>Red cells</b> carry ANTIGENS, and the recipient's plasma carries the
 *       antibodies that attack them. So a group-O recipient (anti-A and anti-B
 *       in their plasma) can only receive O cells, and AB is the universal
 *       <em>recipient</em>. O negative is the universal <em>donor</em>.</li>
 *   <li><b>Plasma</b> carries the ANTIBODIES, so the rule reverses: an AB donor
 *       has neither anti-A nor anti-B, making AB the universal plasma
 *       <em>donor</em> and O the universal plasma <em>recipient</em>.</li>
 * </ul>
 *
 * <p><b>Rh (D)</b> is applied to red-cell-bearing products only: an Rh-negative
 * recipient must not receive Rh-positive cells, because alloimmunisation to D
 * causes haemolytic disease of the fetus and newborn in a later pregnancy —
 * which in a maternal-newborn EHR is not a theoretical concern. An Rh-positive
 * recipient may receive either.
 *
 * <p>⚠ <b>PLATELETS ARE A POLICY CHOICE, NOT A SETTLED RULE.</b> Platelet
 * concentrates carry donor plasma (so the plasma-side ABO rule applies) and
 * residual red cells (so Rh matters for alloimmunisation). Practice varies, and
 * in an emergency ABO-non-identical platelets are routinely given. This class
 * takes the CONSERVATIVE reading — plasma-side ABO plus the Rh rule — which can
 * refuse a pair a particular facility's protocol would allow. That is a
 * deliberate fail-closed default and <b>it needs a haematologist's sign-off
 * before this module is relied on in production</b>, in the same way the V120
 * drug-interaction seed needs a pharmacist's. The emergency-release path exists
 * precisely so a conservative default cannot block a resuscitation.
 */
public enum AboGroup {
    A,
    B,
    AB,
    O;

    /** Donor groups whose RED CELLS this group may receive. */
    public Set<AboGroup> compatibleRedCellDonors() {
        return switch (this) {
            case O -> Set.of(O);
            case A -> Set.of(A, O);
            case B -> Set.of(B, O);
            case AB -> Set.of(A, B, AB, O);
        };
    }

    /** Donor groups whose PLASMA this group may receive. */
    public Set<AboGroup> compatiblePlasmaDonors() {
        return switch (this) {
            case AB -> Set.of(AB);
            case A -> Set.of(A, AB);
            case B -> Set.of(B, AB);
            case O -> Set.of(A, B, AB, O);
        };
    }

    /**
     * Whether a unit of {@code product} from {@code donorGroup}/{@code donorRh}
     * may be given to a recipient of {@code recipientGroup}/{@code recipientRh}.
     *
     * <p>Fail-closed on every axis: a null anywhere is incompatible, because
     * "we don't know" and "it's fine" must never be the same answer here.
     */
    public static boolean isCompatible(AboGroup recipientGroup,
                                       RhFactor recipientRh,
                                       AboGroup donorGroup,
                                       RhFactor donorRh,
                                       BloodProductType product) {
        if (recipientGroup == null || donorGroup == null || product == null) {
            return false;
        }
        boolean aboOk = plasmaSideProduct(product)
            ? recipientGroup.compatiblePlasmaDonors().contains(donorGroup)
            : recipientGroup.compatibleRedCellDonors().contains(donorGroup);
        if (!aboOk) {
            return false;
        }
        if (!rhRelevant(product)) {
            return true;
        }
        if (recipientRh == null || donorRh == null) {
            return false;
        }
        // Rh-positive recipients take either; Rh-negative recipients take only
        // Rh-negative cells.
        return recipientRh == RhFactor.POSITIVE || donorRh == RhFactor.NEGATIVE;
    }

    /** Products whose ABO rule follows the donor's PLASMA rather than their cells. */
    private static boolean plasmaSideProduct(BloodProductType product) {
        return product == BloodProductType.FRESH_FROZEN_PLASMA
            || product == BloodProductType.CRYOPRECIPITATE
            || product == BloodProductType.PLATELETS;
    }

    /** Products carrying enough red cells for D alloimmunisation to matter. */
    private static boolean rhRelevant(BloodProductType product) {
        return product == BloodProductType.WHOLE_BLOOD
            || product == BloodProductType.PACKED_RED_CELLS
            || product == BloodProductType.PLATELETS;
    }

    /**
     * The group released when there is no time to type the patient — group O,
     * Rh negative, the universal red cell donor. Used only on the
     * emergency-release path, which records that this is what happened.
     */
    public static AboGroup emergencyReleaseGroup() {
        return O;
    }

    public static RhFactor emergencyReleaseRh() {
        return RhFactor.NEGATIVE;
    }
}
