package com.example.hms.service.support;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.AboGroup;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.RhFactor;
import com.example.hms.service.support.IncompatibleReleaseAuthority.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Owner decision 2026-11-26 (V381): the pure rule of a release despite an incompatible crossmatch. */
@DisplayName("incompatible release: the ABO block, the ranking of the least incompatible bag, the declarer roles")
class IncompatibleReleaseAuthorityTest {

    /** The textbook red-cell table, written out here and not derived from the code under test. */
    private static final Map<AboGroup, Set<AboGroup>> RED_CELL_DONORS = Map.of(
        AboGroup.O, Set.of(AboGroup.O),
        AboGroup.A, Set.of(AboGroup.A, AboGroup.O),
        AboGroup.B, Set.of(AboGroup.B, AboGroup.O),
        AboGroup.AB, Set.of(AboGroup.A, AboGroup.B, AboGroup.AB, AboGroup.O));

    @Test
    @DisplayName("NEVER EVENT: every ABO-incompatible red-cell pairing is refused, every compatible one passes, for both red-cell products")
    void theAboTableIsExhaustive() {
        for (BloodProductType product : List.of(BloodProductType.PACKED_RED_CELLS, BloodProductType.WHOLE_BLOOD)) {
            for (AboGroup recipient : AboGroup.values()) {
                for (AboGroup donor : AboGroup.values()) {
                    assertThat(IncompatibleReleaseAuthority.aboAllows(recipient, donor, product))
                        .as("%s red cells to a group %s patient (%s)", donor, recipient, product)
                        .isEqualTo(RED_CELL_DONORS.get(recipient).contains(donor));
                }
            }
        }
    }

    @Test
    @DisplayName("an untyped patient receives group O only; a bag with no group, or a product that is not red cells, never passes")
    void unknownIsNotCompatible() {
        for (AboGroup donor : AboGroup.values()) {
            assertThat(IncompatibleReleaseAuthority.aboAllows(null, donor, BloodProductType.PACKED_RED_CELLS))
                .as("%s red cells to an untyped patient", donor).isEqualTo(donor == AboGroup.O);
        }
        assertThat(IncompatibleReleaseAuthority.aboAllows(AboGroup.AB, null, BloodProductType.PACKED_RED_CELLS)).isFalse();
        for (BloodProductType product : BloodProductType.values()) {
            if (!AboGroup.isEmergencyRedCells(product)) {
                assertThat(IncompatibleReleaseAuthority.aboAllows(AboGroup.AB, AboGroup.O, product)).as(product.name()).isFalse();
            }
        }
        assertThat(IncompatibleReleaseAuthority.aboAllows(AboGroup.AB, AboGroup.O, null)).isFalse();
    }

    @Test
    @DisplayName("RhD: a negative bag suits everybody; a positive bag suits a patient typed positive only")
    void rhdMatching() {
        BloodProductType cells = BloodProductType.PACKED_RED_CELLS;
        assertThat(IncompatibleReleaseAuthority.dMatched(null, null, AboGroup.O, RhFactor.NEGATIVE, cells)).isTrue();
        assertThat(IncompatibleReleaseAuthority.dMatched(AboGroup.A, RhFactor.NEGATIVE, AboGroup.A, RhFactor.NEGATIVE, cells)).isTrue();
        assertThat(IncompatibleReleaseAuthority.dMatched(AboGroup.A, RhFactor.POSITIVE, AboGroup.O, RhFactor.POSITIVE, cells)).isTrue();
        assertThat(IncompatibleReleaseAuthority.dMatched(AboGroup.A, RhFactor.NEGATIVE, AboGroup.O, RhFactor.POSITIVE, cells)).isFalse();
        assertThat(IncompatibleReleaseAuthority.dMatched(null, null, AboGroup.O, RhFactor.POSITIVE, cells))
            .as("an untyped patient is not known to be RhD positive").isFalse();
    }

    private static Candidate candidate(String number, boolean dMatched, Boolean kell, Boolean antigenNegative) {
        return new Candidate(UUID.randomUUID(), number, dMatched, kell, antigenNegative);
    }

    @Test
    @DisplayName("least incompatible: RhD first, then RH-KEL1, then the target antigen; « not recorded » ranks as « no »")
    void ranking() {
        Candidate best = candidate("BEST", true, true, true);
        Candidate noAntigen = candidate("NO-ANTIGEN", true, true, null);
        Candidate noKell = candidate("NO-KELL", true, null, true);
        Candidate nothing = candidate("NOTHING", true, null, null);
        Candidate dMismatch = candidate("D-MISMATCH", false, true, true);
        List<Candidate> all = List.of(dMismatch, nothing, noKell, noAntigen, best);

        assertThat(IncompatibleReleaseAuthority.better(best, all)).isEmpty();
        assertThat(IncompatibleReleaseAuthority.better(noAntigen, all)).containsExactly(best);
        assertThat(IncompatibleReleaseAuthority.better(noKell, all)).containsExactlyInAnyOrder(best, noAntigen);
        assertThat(IncompatibleReleaseAuthority.better(nothing, all)).containsExactlyInAnyOrder(best, noAntigen, noKell);
        assertThat(IncompatibleReleaseAuthority.better(dMismatch, all)).as("an RhD mismatch ranks below every RhD match")
            .containsExactlyInAnyOrder(best, noAntigen, noKell, nothing);
    }

    @Test
    @DisplayName("NEVER BLOCKS: the only bag there is, and a bag tied with another, are the least incompatible")
    void theOnlyBagIsTheLeastIncompatible() {
        Candidate only = candidate("ONLY", false, null, null);
        assertThat(IncompatibleReleaseAuthority.better(only, List.of(only))).isEmpty();
        Candidate twin = candidate("TWIN", false, null, null);
        assertThat(IncompatibleReleaseAuthority.better(only, List.of(only, twin))).as("a tie holds nothing back").isEmpty();
        assertThat(IncompatibleReleaseAuthority.better(twin, List.of(only, twin))).isEmpty();
    }

    @Test
    @DisplayName("who may declare: laboratory staff by default; the stored setting keeps laboratory roles only and never means « nobody »")
    void declarerRoles() {
        assertThat(IncompatibleReleaseAuthority.DEFAULT_DECLARER_ROLES).containsExactlyInAnyOrder(
            SecurityConstants.ROLE_LAB_SCIENTIST, SecurityConstants.ROLE_LAB_TECHNICIAN,
            SecurityConstants.ROLE_LAB_MANAGER, SecurityConstants.ROLE_LAB_DIRECTOR);
        assertThat(IncompatibleReleaseAuthority.Settings.NO_PROTOCOL.fallbackEnabled()).as("off until switched on").isFalse();
        assertThat(IncompatibleReleaseAuthority.parseRoles("ROLE_LAB_MANAGER, role_lab_scientist"))
            .containsExactly(SecurityConstants.ROLE_LAB_SCIENTIST, SecurityConstants.ROLE_LAB_MANAGER);
        assertThat(IncompatibleReleaseAuthority.parseRoles("ROLE_MIDWIFE,ROLE_LAB_DIRECTOR"))
            .as("a role outside the laboratory is not one the setting can name")
            .containsExactly(SecurityConstants.ROLE_LAB_DIRECTOR);
        for (String empty : Arrays.asList(null, "", " , ", "ROLE_NURSE")) {
            assertThat(IncompatibleReleaseAuthority.parseRoles(empty)).as("« %s »", empty)
                .containsExactlyElementsOf(IncompatibleReleaseAuthority.DECLARER_ROLE_UNIVERSE);
        }
        assertThat(IncompatibleReleaseAuthority.formatRoles(Set.of(SecurityConstants.ROLE_LAB_MANAGER,
            SecurityConstants.ROLE_LAB_SCIENTIST))).isEqualTo("ROLE_LAB_SCIENTIST,ROLE_LAB_MANAGER");
    }
}
