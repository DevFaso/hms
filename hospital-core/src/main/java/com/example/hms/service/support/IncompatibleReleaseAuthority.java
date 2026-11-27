package com.example.hms.service.support;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.AboGroup;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.ChildbearingPotential;
import com.example.hms.enums.RhFactor;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The rule for releasing red cells despite an incompatible crossmatch (owner decision 2026-11-26, option B of the
 * research report « Incompatible blood release authority »; rule set INCOMPATIBLE_RELEASE_AUTHORITY,
 * « provisoire », to be signed off by the médecin-chef and a CNTS/CRTS biologist). The one copy: the decision
 * service applies it and tells the portal what it says.
 *
 * <ul>
 *   <li><b>The normal path</b>: a physician or the laboratory director, named, who is not the person issuing.</li>
 *   <li><b>The fallback</b>, where the laboratory director switched it on for the hospital: no physician can be
 *       reached, the issuer says so with a reason, and the least incompatible bag goes out under the written
 *       protocol with the authority owed. A physician countersigns within the deadline of the hospital.</li>
 *   <li><b>Never an ABO-excluded bag</b>, on any path, whoever asks: {@link #aboAllows}.</li>
 * </ul>
 *
 * <p>This class decides and ranks; it refuses nothing. The refusals are the decision service's.
 */
@ProvisionalRule(ClinicalRuleSetKey.INCOMPATIBLE_RELEASE_AUTHORITY)
public final class IncompatibleReleaseAuthority {

    /** Who may authorise on the normal path, and countersign a fallback release. */
    public static final Set<String> AUTHORISER_ROLE_CODES = EmergencyReleasePolicy.INCOMPATIBLE_AUTHORISER_ROLE_CODES;

    /**
     * The roles a hospital may allow to declare that no physician can be reached. The declaration is made by the
     * person issuing the bag, and only laboratory staff issue: widening this beyond the laboratory (a midwife, a
     * senior nurse) is one of the points the clinical sign-off must settle first.
     */
    public static final List<String> DECLARER_ROLE_UNIVERSE = List.of(
        SecurityConstants.ROLE_LAB_SCIENTIST, SecurityConstants.ROLE_LAB_TECHNICIAN,
        SecurityConstants.ROLE_LAB_MANAGER, SecurityConstants.ROLE_LAB_DIRECTOR);

    /** The default of the setting: laboratory staff only, all of them. */
    public static final Set<String> DEFAULT_DECLARER_ROLES = Set.copyOf(DECLARER_ROLE_UNIVERSE);

    /** The staged start of the transfusion, in millilitres: 20, then 50, then the rest. */
    public static final int SLOW_START_FIRST_ML = 20;
    public static final int SLOW_START_SECOND_ML = 50;

    /** The settings of one hospital. With no row the fallback is off. */
    public record Settings(boolean fallbackEnabled, Set<String> declarerRoles, int countersignDeadlineHours) {

        public static final Settings NO_PROTOCOL = new Settings(false, DEFAULT_DECLARER_ROLES,
            OPositiveReleaseAuthority.DEFAULT_REVIEW_DEADLINE_HOURS);
    }

    /**
     * One bag with an incompatible result on the request, as the ranking reads it.
     *
     * @param dMatched        the RhD of the bag suits the patient (an RhD negative bag suits everybody)
     * @param rhKellMatched   the laboratory recorded the bag as RH-KEL1 matched; null when it recorded nothing
     * @param antigenNegative the laboratory recorded the bag as negative for the target antigen; null when nothing
     */
    public record Candidate(UUID unitId, String unitNumber, boolean dMatched, Boolean rhKellMatched, Boolean antigenNegative) {

        /** Lower is less incompatible: RhD first, then RH-KEL1, then the target antigen. */
        public int rank() {
            return (dMatched ? 0 : 4) + (Boolean.TRUE.equals(rhKellMatched) ? 0 : 2) + (Boolean.TRUE.equals(antigenNegative) ? 0 : 1);
        }
    }

    /**
     * THE NEVER EVENT. Whether the ABO group of a red-cell bag suits this recipient: group O suits everybody; any
     * other group needs the group of the patient on record, and that group must accept it. An untyped patient
     * receives group O only. False for anything that is not red cells: this rule releases nothing else.
     */
    public static boolean aboAllows(AboGroup recipientAbo, AboGroup donorAbo, BloodProductType product) {
        if (!AboGroup.isEmergencyRedCells(product) || donorAbo == null) {
            return false;
        }
        if (donorAbo == AboGroup.emergencyReleaseGroup()) {
            return true;
        }
        return recipientAbo != null && recipientAbo.compatibleRedCellDonors().contains(donorAbo);
    }

    /** Whether the RhD of the bag suits the patient, by the rules of {@link AboGroup#isCompatible}. */
    public static boolean dMatched(AboGroup recipientAbo, RhFactor recipientRh, AboGroup donorAbo, RhFactor donorRh,
                                   BloodProductType product) {
        if (donorRh == RhFactor.NEGATIVE) {
            return true;
        }
        return AboGroup.isCompatible(recipientAbo, recipientRh, donorAbo, donorRh, product, ChildbearingPotential.UNKNOWN);
    }

    /** The bags that rank strictly better than the one chosen; empty when the choice is the least incompatible. */
    public static List<Candidate> better(Candidate chosen, Collection<Candidate> all) {
        List<Candidate> better = new ArrayList<>();
        for (Candidate other : all) {
            if (!other.unitId().equals(chosen.unitId()) && other.rank() < chosen.rank()) {
                better.add(other);
            }
        }
        return better;
    }

    /** The stored setting read back: the roles of the universe it names, in the order of the universe. */
    public static Set<String> parseRoles(String stored) {
        Set<String> named = new LinkedHashSet<>();
        if (stored != null) {
            for (String part : stored.split(",")) {
                named.add(part.trim().toUpperCase(Locale.ROOT));
            }
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String role : DECLARER_ROLE_UNIVERSE) {
            if (named.contains(role)) {
                roles.add(role);
            }
        }
        // A stored value that names nothing usable is the default, never « nobody »: the switch is the off state.
        return roles.isEmpty() ? new LinkedHashSet<>(DECLARER_ROLE_UNIVERSE) : roles;
    }

    public static String formatRoles(Collection<String> roles) {
        List<String> ordered = new ArrayList<>();
        for (String role : DECLARER_ROLE_UNIVERSE) {
            if (roles.contains(role)) {
                ordered.add(role);
            }
        }
        return String.join(",", ordered);
    }

    private IncompatibleReleaseAuthority() {
    }
}
