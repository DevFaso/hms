package com.example.hms.service.support;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.config.SecurityConstants;
import com.example.hms.security.RoleExpansion;

import java.util.Set;

/**
 * Who may answer for an emergency release (user-supplied guidance 2026-11-06). The one copy of these role
 * sets: the service enforces them, and tells the portal what they say for one request.
 *
 * <p>Who may RECEIVE O positive, and on whose decision, is no longer decided here: since the owner decision of
 * 2026-11-25 it is {@link OPositiveReleaseAuthority} (rule set EMERGENCY_O_POSITIVE_AUTHORITY), which tiers the
 * patient by sex, age, antibody and the hospital's protocol. The roles below are local choices informed by the
 * guidance, not requirements prescribed by AABB.
 */
@ProvisionalRule(ClinicalRuleSetKey.BLOOD_EMERGENCY_RELEASE)
public final class EmergencyReleasePolicy {

    /**
     * Who may authorise a release despite an incompatible crossmatch. There is no transfusion
     * physician role. A physician or surgeon is a doctor by {@link RoleExpansion}'s equivalence.
     */
    public static final Set<String> INCOMPATIBLE_AUTHORISER_ROLE_CODES = Set.of(
        SecurityConstants.ROLE_DOCTOR, RoleExpansion.ROLE_PHYSICIAN, SecurityConstants.ROLE_SURGEON,
        SecurityConstants.ROLE_LAB_DIRECTOR);

    /**
     * Who may be named as making an O positive decision in the LOW-RISK tier, where the issuer may decide
     * alone under the hospital's protocol: whoever may order blood or run the bench here. Outside that tier
     * the decision is a physician's ({@link OPositiveReleaseAuthority#HIGH_RISK_DECIDER_ROLE_CODES}).
     */
    public static final Set<String> O_POSITIVE_DECIDER_ROLE_CODES = Set.of(
        SecurityConstants.ROLE_DOCTOR, RoleExpansion.ROLE_PHYSICIAN, SecurityConstants.ROLE_SURGEON,
        SecurityConstants.ROLE_MIDWIFE, SecurityConstants.ROLE_LAB_DIRECTOR, SecurityConstants.ROLE_LAB_MANAGER,
        SecurityConstants.ROLE_LAB_SCIENTIST, SecurityConstants.ROLE_LAB_TECHNICIAN);

    private EmergencyReleasePolicy() {
    }
}
