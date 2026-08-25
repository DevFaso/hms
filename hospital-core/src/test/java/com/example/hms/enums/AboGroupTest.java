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
            BloodProductType.PACKED_RED_CELLS)).isEqualTo(expected);
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
            BloodProductType.FRESH_FROZEN_PLASMA)).isEqualTo(expected);
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
            BloodProductType.PACKED_RED_CELLS)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.NEGATIVE, AboGroup.O, RhFactor.NEGATIVE,
            BloodProductType.PACKED_RED_CELLS)).isTrue();
    }

    @Test
    void rhPositiveRecipientsMayReceiveEither() {
        assertThat(AboGroup.isCompatible(AboGroup.A, RhFactor.POSITIVE, AboGroup.O, RhFactor.NEGATIVE,
            BloodProductType.PACKED_RED_CELLS)).isTrue();
        assertThat(AboGroup.isCompatible(AboGroup.A, RhFactor.POSITIVE, AboGroup.O, RhFactor.POSITIVE,
            BloodProductType.PACKED_RED_CELLS)).isTrue();
    }

    @Test
    void rhIsNotAppliedToAcellularPlasmaProducts() {
        // FFP and cryo carry no meaningful red cells, so D status does not gate
        // them; an Rh-negative recipient may receive Rh-positive plasma.
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.NEGATIVE, AboGroup.AB, RhFactor.POSITIVE,
            BloodProductType.FRESH_FROZEN_PLASMA)).isTrue();
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.NEGATIVE, AboGroup.AB, RhFactor.POSITIVE,
            BloodProductType.CRYOPRECIPITATE)).isTrue();
    }

    @Test
    void plateletsTakeTheConservativeReadingOnBothAxes() {
        // Documented as a POLICY CHOICE awaiting haematologist sign-off:
        // plasma-side ABO (platelet concentrates carry donor plasma) plus Rh
        // (residual red cells alloimmunise). This can refuse a pair a given
        // protocol allows — the emergency path is what keeps that from
        // blocking a resuscitation.
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.POSITIVE, AboGroup.A, RhFactor.POSITIVE,
            BloodProductType.PLATELETS)).isTrue();
        assertThat(AboGroup.isCompatible(AboGroup.AB, RhFactor.POSITIVE, AboGroup.O, RhFactor.POSITIVE,
            BloodProductType.PLATELETS)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.O, RhFactor.NEGATIVE, AboGroup.O, RhFactor.POSITIVE,
            BloodProductType.PLATELETS)).isFalse();
    }

    // ── Fail-closed ──────────────────────────────────────────────────────

    @Test
    void anyMissingFactMeansIncompatible() {
        assertThat(AboGroup.isCompatible(null, RhFactor.POSITIVE, AboGroup.O, RhFactor.POSITIVE,
            BloodProductType.PACKED_RED_CELLS)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.AB, RhFactor.POSITIVE, null, RhFactor.POSITIVE,
            BloodProductType.PACKED_RED_CELLS)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.AB, RhFactor.POSITIVE, AboGroup.O, RhFactor.POSITIVE,
            null)).isFalse();
        // Unknown Rh on a red-cell product is NOT treated as positive-and-fine.
        assertThat(AboGroup.isCompatible(AboGroup.AB, null, AboGroup.O, RhFactor.NEGATIVE,
            BloodProductType.PACKED_RED_CELLS)).isFalse();
        assertThat(AboGroup.isCompatible(AboGroup.AB, RhFactor.NEGATIVE, AboGroup.O, null,
            BloodProductType.PACKED_RED_CELLS)).isFalse();
    }

    @Test
    void oNegativeIsTheEmergencyReleaseAndClearsEveryRedCellRecipient() {
        assertThat(AboGroup.emergencyReleaseGroup()).isEqualTo(AboGroup.O);
        assertThat(AboGroup.emergencyReleaseRh()).isEqualTo(RhFactor.NEGATIVE);
        for (AboGroup recipient : AboGroup.values()) {
            for (RhFactor rh : RhFactor.values()) {
                assertThat(AboGroup.isCompatible(recipient, rh,
                    AboGroup.emergencyReleaseGroup(), AboGroup.emergencyReleaseRh(),
                    BloodProductType.PACKED_RED_CELLS))
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
