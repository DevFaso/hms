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
 * <p><b>PLATELETS FOLLOW THIS FACILITY'S SIGNED-OFF PROTOCOL, NOT A TEXTBOOK
 * DEFAULT.</b> Haematologist sign-off 2026-08-25 replaced the conservative
 * plasma-side reading this class originally shipped with. Two changes:
 *
 * <ul>
 *   <li><b>ABO:</b> compatibility is prioritised but incompatibility is
 *       acceptable, EXCEPT that group O platelets are not given to A or AB
 *       recipients. Note the asymmetry — a B recipient may receive O
 *       platelets. That is the protocol as signed off, not an oversight.</li>
 *   <li><b>Rh:</b> the restriction protects a future pregnancy, so it applies
 *       to females under 55 and not to males or to females 55 and over. See
 *       {@link ChildbearingPotential}, which fails closed on an unrecognised
 *       or missing sex or age. Where it does apply it may be overridden with
 *       a recorded reason.</li>
 * </ul>
 *
 * <p>Plasma and cryoprecipitate carry NO Rh restriction — also confirmed at
 * sign-off.
 *
 * <p>Changing any of this needs a haematologist, not a code review. The
 * emergency-release path remains available so no rule here can block a
 * resuscitation.
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
     * Donor groups whose PLATELETS this group may receive.
     *
     * <p>Set by haematologist sign-off on 2026-08-25, replacing the
     * conservative plasma-side reading this class shipped with. <b>ABO
     * compatibility is prioritised but ABO incompatibility is acceptable
     * for platelets</b>, with one exclusion: <b>group O platelets are not
     * given to A or AB recipients.</b>
     *
     * <p>Note the asymmetry — a B recipient MAY receive O platelets under
     * this protocol while an A or AB recipient may not. That is the rule as
     * signed off, and it is implemented exactly as written; it must not be
     * "tidied" into symmetry by a later reader.
     *
     * <p><b>Whether that asymmetry is intended remains an OPEN clinical
     * question</b>, deliberately left open by the product owner on
     * 2026-08-26 rather than assumed either way. It is not a blocker — the
     * rule is permissive here, so the software does exactly what the
     * sign-off said — but it has never been confirmed as protocol rather
     * than a transcription slip. {@link #isPlateletPairingPendingConfirmation}
     * identifies the pairing so the question is visible to the people who
     * can settle it instead of living only in this comment.
     */
    public Set<AboGroup> compatiblePlateletDonors() {
        return switch (this) {
            case O -> Set.of(O, A, B, AB);
            case B -> Set.of(O, A, B, AB);
            case A -> Set.of(A, B, AB);
            case AB -> Set.of(A, B, AB);
        };
    }

    /**
     * The one pairing this facility's platelet protocol permits and nobody
     * has yet confirmed was meant: <b>group O platelets to a group B
     * recipient</b>.
     *
     * <p>The 2026-08-25 sign-off excluded O platelets for A and AB recipients
     * and not for B. That is implemented as written and this method does NOT
     * refuse it — the transfusion goes ahead. What it does is let the pairing
     * be named wherever it occurs, so an open clinical question is visible to
     * a haematologist rather than buried in a javadoc that only developers
     * read. Product-owner decision, 2026-08-26: keep the question open and
     * make it known.
     *
     * <p>Delete this method the day the asymmetry is confirmed or corrected.
     * It carries no rule of its own and has no reason to outlive the answer.
     */
    public static boolean isPlateletPairingPendingConfirmation(AboGroup recipientGroup,
                                                               AboGroup donorGroup,
                                                               BloodProductType product) {
        return product == BloodProductType.PLATELETS
            && recipientGroup == B
            && donorGroup == O;
    }

    /**
     * Whether a unit of {@code product} from {@code donorGroup}/{@code donorRh}
     * may be given to a recipient of {@code recipientGroup}/{@code recipientRh}.
     *
     * <p>Fail-closed on every axis: a null anywhere is incompatible, because
     * "we don't know" and "it's fine" must never be the same answer here.
     *
     * @param childbearingPotential governs the platelet Rh rule only. The
     *     restriction protects a future pregnancy, so it applies to females
     *     under 55 and not to males or older females; UNKNOWN protects. Pass
     *     {@link ChildbearingPotential#UNKNOWN} when the caller has no
     *     patient context — it is the safe default, not a neutral one.
     */
    public static boolean isCompatible(AboGroup recipientGroup,
                                       RhFactor recipientRh,
                                       AboGroup donorGroup,
                                       RhFactor donorRh,
                                       BloodProductType product,
                                       ChildbearingPotential childbearingPotential) {
        if (recipientGroup == null || donorGroup == null || product == null) {
            return false;
        }
        if (!aboCompatible(recipientGroup, donorGroup, product)) {
            return false;
        }
        if (!rhRelevant(product)) {
            return true;
        }
        if (product == BloodProductType.PLATELETS
                && childbearingPotential != null
                && !childbearingPotential.requiresRhProtection()) {
            // Male, or female 55+: no D-alloimmunisation restriction on
            // platelets for this recipient.
            return true;
        }
        if (recipientRh == null || donorRh == null) {
            return false;
        }
        // Rh-positive recipients take either; Rh-negative recipients take only
        // Rh-negative cells.
        return recipientRh == RhFactor.POSITIVE || donorRh == RhFactor.NEGATIVE;
    }

    /**
     * Whether the ABO pairing is acceptable for this product.
     *
     * <p>Three rules, not two: red cells follow the donor's cells, plasma and
     * cryoprecipitate follow the donor's plasma, and platelets have their own
     * (see {@link #compatiblePlateletDonors()}).
     */
    private static boolean aboCompatible(AboGroup recipientGroup,
                                         AboGroup donorGroup,
                                         BloodProductType product) {
        if (product == BloodProductType.PLATELETS) {
            return recipientGroup.compatiblePlateletDonors().contains(donorGroup);
        }
        if (plasmaSideProduct(product)) {
            return recipientGroup.compatiblePlasmaDonors().contains(donorGroup);
        }
        return recipientGroup.compatibleRedCellDonors().contains(donorGroup);
    }

    /** Products whose ABO rule follows the donor's PLASMA rather than their cells. */
    private static boolean plasmaSideProduct(BloodProductType product) {
        return product == BloodProductType.FRESH_FROZEN_PLASMA
            || product == BloodProductType.CRYOPRECIPITATE;
    }

    /**
     * Products carrying enough red cells for D alloimmunisation to matter.
     *
     * <p>Plasma and cryoprecipitate are deliberately absent — confirmed at
     * sign-off: no Rh restriction is needed for plasma.
     */
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
