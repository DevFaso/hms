package com.example.hms.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ABO/Rh compatibility rules (Tier 2 item 28).
 *
 * <p>An ABO-incompatible red cell transfusion is a never-event that kills
 * people, so these rules are pinned exhaustively rather than sampled: every
 * one of the 16 donor/recipient ABO pairs is asserted in both directions
 * (red cells and plasma), because the rule REVERSES between them and that
 * reversal is the classic error.
 */
class AboGroupTest {

    // ── Red cells: recipient antibodies attack donor antigens ────────────

    @ParameterizedTest(name = "red cells: {0} may receive {1} = {2}")
    @CsvSource({
        // O recipients have anti-A and anti-B — O cells only.
        "O,  O,  true", "O,  A,  false", "O,  B,  false", "O,  AB, false",
        // A recipients have anti-B.
        "A,  A,  true", "A,  O,  true",  "A,  B,  false", "A,  AB, false",
        // B recipients have anti-A.
        "B,  B,  true", "B,  O,  true",  "B,  A,  false", "B,  AB, false",
        // AB recipients have neither — the universal recipient.
        "AB, AB, true", "AB, A,  true",  "AB, B,  true",  "AB, O,  true",
    })
    void redCellCompatibility(AboGroup recipient, AboGroup donor, boolean expected) {
        assertThat(AboGroup.isCompatible(recipient, RhFactor.POSITIVE, donor, RhFactor.POSITIVE,
            BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.UNKNOWN)).isEqualTo(expected);
    }

    // ── Plasma: the rule reverses, because plasma carries the antibodies ─

    @ParameterizedTest(name = "plasma: {0} may receive {1} = {2}")
    @CsvSource({
        // AB donors have no anti-A or anti-B — the universal plasma donor.
        "AB, AB, true", "AB, A,  false", "AB, B,  false", "AB, O,  false",
        "A,  A,  true", "A,  AB, true",  "A,  B,  false", "A,  O,  false",
        "B,  B,  true", "B,  AB, true",  "B,  A,  false", "B,  O,  false",
        // O recipients accept any plasma — the universal plasma recipient.
        "O,  O,  true", "O,  A,  true",  "O,  B,  true",  "O,  AB, true",
    })
    void plasmaCompatibility(AboGroup recipient, AboGroup donor, boolean expected) {
        assertThat(AboGroup.isCompatible(recipient, RhFactor.POSITIVE, donor, RhFactor.POSITIVE,
            BloodProductType.FRESH_FROZEN_PLASMA, ChildbearingPotential.UNKNOWN)).isEqualTo(expected);
    }

    @Test
    void plasmaRuleIsTheExactInverseOfTheRedCellRuleForOAndAb() {
        // The single most consequential asymmetry in the module: O is the
        // universal RED CELL donor but the universal PLASMA recipient.
        assertThat(AboGroup.O.compatibleRedCellDonors()).containsExactly(AboGroup.O);
        assertThat(AboGroup.O.compatiblePlasmaDonors())
            .containsExactlyInAnyOrder(AboGroup.A, AboGroup.B, AboGroup.AB, AboGroup.O);
        assertThat(AboGroup.AB.compatibleRedCellDonors())
            .containsExactlyInAnyOrder(AboGroup.A, AboGroup.B, AboGroup.AB, AboGroup.O);
        assertThat(AboGroup.AB.compatiblePlasmaDonors()).containsExactly(AboGroup.AB);
    }

    // ── Rh ───────────────────────────────────────────────────────────────

    @Test
    void rhNegativeRecipientsMayNotReceiveRhPositiveRedCells() {
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.NEGATIVE, AboGroup.O, RhFactor.POSITIVE,
            BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.UNKNOWN)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.NEGATIVE, AboGroup.O, RhFactor.NEGATIVE,
            BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.UNKNOWN)).isTrue();
    }

    @Test
    void rhPositiveRecipientsMayReceiveEither() {
        assertThat(AboGroup.isCompatible(AboGroup.A, RhFactor.POSITIVE, AboGroup.O, RhFactor.NEGATIVE,
            BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.UNKNOWN)).isTrue();
        assertThat(AboGroup.isCompatible(AboGroup.A, RhFactor.POSITIVE, AboGroup.O, RhFactor.POSITIVE,
            BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.UNKNOWN)).isTrue();
    }

    @Test
    void rhIsNotAppliedToAcellularPlasmaProducts() {
        // FFP and cryo carry no meaningful red cells, so D status does not gate
        // them; an Rh-negative recipient may receive Rh-positive plasma.
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.NEGATIVE, AboGroup.AB, RhFactor.POSITIVE,
            BloodProductType.FRESH_FROZEN_PLASMA, ChildbearingPotential.UNKNOWN)).isTrue();
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.NEGATIVE, AboGroup.AB, RhFactor.POSITIVE,
            BloodProductType.CRYOPRECIPITATE, ChildbearingPotential.UNKNOWN)).isTrue();
    }

    // ── Platelets: this facility's protocol, signed off 2026-08-25 ───────
    //
    // NOT the plasma-side default this module originally shipped with. ABO
    // compatibility is prioritised but incompatibility is acceptable, with
    // one exclusion: O platelets are not given to A or AB recipients.

    @ParameterizedTest(name = "platelets: {0} may receive {1} = {2}")
    @CsvSource({
        // The exclusion, and only the exclusion.
        "A,  O,  false", "AB, O,  false",
        // Everything else passes, INCLUDING O to B. The asymmetry is the
        // protocol as signed off, not a transcription slip — a later reader
        // must not "tidy" B into matching A.
        "B,  O,  true",  "O,  O,  true",
        "A,  A,  true",  "A,  B,  true",  "A,  AB, true",
        "B,  A,  true",  "B,  B,  true",  "B,  AB, true",
        "AB, A,  true",  "AB, B,  true",  "AB, AB, true",
        "O,  A,  true",  "O,  B,  true",  "O,  AB, true",
    })
    void plateletAboFollowsTheSignedOffProtocol(AboGroup recipient, AboGroup donor, boolean expected) {
        assertThat(AboGroup.isCompatible(recipient, RhFactor.POSITIVE, donor, RhFactor.POSITIVE,
            BloodProductType.PLATELETS, ChildbearingPotential.NO)).isEqualTo(expected);
    }

    @Test
    void plateletAboIsNotThePlasmaRule() {
        // Guards the specific regression of reverting to the pre-sign-off
        // reading: under plasma rules an AB recipient could take ONLY AB,
        // and an A recipient could not take B.
        assertThat(AboGroup.AB.compatiblePlateletDonors())
            .containsExactlyInAnyOrder(AboGroup.A, AboGroup.B, AboGroup.AB);
        assertThat(AboGroup.A.compatiblePlateletDonors())
            .containsExactlyInAnyOrder(AboGroup.A, AboGroup.B, AboGroup.AB);
        assertThat(AboGroup.AB.compatiblePlasmaDonors()).containsExactly(AboGroup.AB);
    }

    // ── Platelet Rh: turns on who the restriction protects ───────────────

    @Test
    void anRhNegativeWomanUnderFiftyFiveIsProtectedFromRhPositivePlatelets() {
        assertThat(AboGroup.isCompatible(AboGroup.A, RhFactor.NEGATIVE, AboGroup.A, RhFactor.POSITIVE,
            BloodProductType.PLATELETS, ChildbearingPotential.YES)).isFalse();
    }

    @Test
    void aManOrOlderWomanMayReceiveRhPositivePlatelets() {
        // The restriction exists to protect a future pregnancy, so it does
        // not apply here.
        assertThat(AboGroup.isCompatible(AboGroup.A, RhFactor.NEGATIVE, AboGroup.A, RhFactor.POSITIVE,
            BloodProductType.PLATELETS, ChildbearingPotential.NO)).isTrue();
    }

    @Test
    void unknownChildbearingPotentialProtectsRatherThanPermits() {
        // gender is a free-text column with no canonical vocabulary, so
        // UNKNOWN is common and must not read as "no restriction".
        assertThat(AboGroup.isCompatible(AboGroup.A, RhFactor.NEGATIVE, AboGroup.A, RhFactor.POSITIVE,
            BloodProductType.PLATELETS, ChildbearingPotential.UNKNOWN)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.A, RhFactor.NEGATIVE, AboGroup.A, RhFactor.POSITIVE,
            BloodProductType.PLATELETS, null)).isFalse();
    }

    @Test
    void theRhExemptionNeverRelaxesTheAboExclusion() {
        // Being male does not make O platelets acceptable for an A recipient.
        assertThat(AboGroup.isCompatible(AboGroup.A, RhFactor.POSITIVE, AboGroup.O, RhFactor.POSITIVE,
            BloodProductType.PLATELETS, ChildbearingPotential.NO)).isFalse();
    }

    @Test
    void theRhExemptionDoesNotLeakIntoRedCells() {
        // Red cells keep the unconditional Rh rule for every recipient.
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.NEGATIVE, AboGroup.O, RhFactor.POSITIVE,
            BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.NO)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.NEGATIVE, AboGroup.O, RhFactor.POSITIVE,
            BloodProductType.WHOLE_BLOOD, ChildbearingPotential.NO)).isFalse();
    }

    // ── Fail-closed ──────────────────────────────────────────────────────

    @Test
    void anyMissingFactMeansIncompatible() {
        assertThat(AboGroup.isCompatible(null, RhFactor.POSITIVE, AboGroup.O, RhFactor.POSITIVE,
            BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.UNKNOWN)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.AB, RhFactor.POSITIVE, null, RhFactor.POSITIVE,
            BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.UNKNOWN)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.AB, RhFactor.POSITIVE, AboGroup.O, RhFactor.POSITIVE,
            null, ChildbearingPotential.UNKNOWN)).isFalse();
        // Unknown Rh on a red-cell product is NOT treated as positive-and-fine.
        assertThat(AboGroup.isCompatible(AboGroup.AB, null, AboGroup.O, RhFactor.NEGATIVE,
            BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.UNKNOWN)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.AB, RhFactor.NEGATIVE, AboGroup.O, null,
            BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.UNKNOWN)).isFalse();
    }

    @Test
    void oNegativeIsTheEmergencyReleaseAndClearsEveryRedCellRecipient() {
        assertThat(AboGroup.emergencyReleaseGroup()).isEqualTo(AboGroup.O);
        assertThat(AboGroup.emergencyReleaseRh()).isEqualTo(RhFactor.NEGATIVE);
        for (AboGroup recipient : AboGroup.values()) {
            for (RhFactor rh : RhFactor.values()) {
                assertThat(AboGroup.isCompatible(recipient, rh,
                    AboGroup.emergencyReleaseGroup(), AboGroup.emergencyReleaseRh(),
                    BloodProductType.PACKED_RED_CELLS, ChildbearingPotential.UNKNOWN))
                    .as("O negative must clear %s %s", recipient, rh)
                    .isTrue();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(AboGroup.class)
    void everyGroupCanReceiveItsOwnRedCellsAndPlasma(AboGroup group) {
        assertThat(group.compatibleRedCellDonors()).contains(group);
        assertThat(group.compatiblePlasmaDonors()).contains(group);
    }
}
