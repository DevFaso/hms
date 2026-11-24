package com.example.hms.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The one rule for which roles a session may carry.
 *
 * <p>A session's role set is fixed when it is opened: the role picked at the
 * password sign-in (or the single role held), the same role carried through
 * the MFA challenge, and nothing else. Every later token of that session (a
 * refresh, the session the MFA code completes) carries the roles it was
 * opened with that the account <em>still</em> holds, never a role it holds
 * now but did not sign in with: the second factor is decided on the roles
 * of the sign-in.
 *
 * <p>A sign-in path mints a session only from a {@link Granted} set, and a
 * {@link Granted} set is made in two places only: {@link #atSignIn} (the
 * password sign-in's own roles) and {@link #stillHeld} (a continued session).
 * {@code SessionRoleScopeGuardTest} holds the sign-in paths to that.
 */
public final class SessionRoles {

    private SessionRoles() {
    }

    /**
     * A role set a session may be minted with. Only {@link SessionRoles} makes
     * one; an empty set is never minted (the caller refuses the sign-in).
     */
    public static final class Granted {

        private final List<String> roles;

        private Granted(List<String> roles) {
            this.roles = List.copyOf(roles);
        }

        public List<String> roles() {
            return roles;
        }

        public boolean isEmpty() {
            return roles.isEmpty();
        }

        /** The token descriptor for this set: the only way a sign-in path builds one. */
        public TokenUserDescriptor descriptor(UUID userId, String username) {
            return new TokenUserDescriptor(userId, username, roles);
        }
    }

    /**
     * The roles a password sign-in opens its session with: the role picked,
     * or the single role held, as the login decided them from the account it
     * has just authenticated.
     */
    public static Granted atSignIn(Collection<String> effectiveRoles) {
        return new Granted(effectiveRoles == null ? List.of()
            : effectiveRoles.stream().filter(Objects::nonNull).toList());
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
    public static Granted stillHeld(Collection<String> carried, Collection<String> heldCodes) {
        if (carried == null || carried.isEmpty() || heldCodes == null || heldCodes.isEmpty()) {
            return new Granted(List.of());
        }
        Set<String> held = RoleExpansion.expand(heldCodes.stream().filter(Objects::nonNull).toList());
        return new Granted(carried.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .flatMap(role -> held.stream().filter(role::equalsIgnoreCase).limit(1))
            .distinct()
            .toList());
    }
}
