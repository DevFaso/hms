package com.example.hms.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The one rule for which roles a continued session may carry.
 *
 * <p>A session's role set is fixed when it is opened: the role picked at the
 * password sign-in (or the single role held), the same role carried through
 * the MFA challenge, and nothing else. Every later token of that session (a
 * refresh, the session the MFA code completes) carries the roles it was
 * opened with that the account <em>still</em> holds, never a role it holds
 * now but did not sign in with: the second factor is decided on the roles
 * of the sign-in.
 *
 * <p>So the result is always a subset of {@code carried}: a role removed since
 * sign-in drops out, an assignment granted since sign-in needs a new sign-in.
 * An empty result means the session can no longer continue; the caller
 * refuses it rather than minting a token with no role.
 */
public final class SessionRoles {

    private SessionRoles() {
    }

    /**
     * The roles of {@code carried} the account still holds, in carried order,
     * with the held spelling (the MFA required-roles check, the idle gate's
     * machine-role carve-out and the response compare exact strings).
     *
     * <p>{@code heldCodes} are the account's own role codes (its active
     * assignments and legacy global roles, {@link EffectiveRoles#codes}, or a
     * principal's authorities); they are widened as the sign-in widened them
     * ({@link RoleExpansion#expand}: a surgeon's DOCTOR, a super-admin's
     * inherited roles), so a role picked from that widening still counts as
     * held. The widening only decides what is still held; it never adds a
     * role to the session.
     *
     * @param carried   the roles the session was opened with (a refresh token's
     *                  {@code roles}, an MFA challenge's picked roles)
     * @param heldCodes the role codes the account holds now
     * @return {@code carried} ∩ held; empty when none is still held
     */
    public static List<String> stillHeld(Collection<String> carried, Collection<String> heldCodes) {
        if (carried == null || carried.isEmpty() || heldCodes == null || heldCodes.isEmpty()) {
            return List.of();
        }
        Set<String> held = RoleExpansion.expand(heldCodes.stream().filter(Objects::nonNull).toList());
        return carried.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .flatMap(role -> held.stream().filter(role::equalsIgnoreCase).limit(1))
            .distinct()
            .toList();
    }
}
