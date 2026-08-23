/**
 * Doctor equivalence (2026-08-23 role audit, decision C2): physicians and
 * surgeons ARE doctors — every guard, nav gate and permission check naming
 * ROLE_DOCTOR admits them through this expansion instead of each list
 * carrying three role names. Mirrors the backend authority expansion in
 * JwtTokenProvider.getAuthenticationFromJwt / SecurityConfig.authoritiesMapper.
 */
export const DOCTOR_EQUIVALENT_ROLES: ReadonlySet<string> = new Set([
  'ROLE_PHYSICIAN',
  'ROLE_SURGEON',
]);

/** Held roles plus the equivalents they imply (currently only ROLE_DOCTOR). */
export function expandRoleEquivalents(roles: string[]): string[] {
  if (roles.some((r) => DOCTOR_EQUIVALENT_ROLES.has(r)) && !roles.includes('ROLE_DOCTOR')) {
    return [...roles, 'ROLE_DOCTOR'];
  }
  return roles;
}

/** True when the single held/active role satisfies any of the required roles. */
export function roleSatisfies(required: string[], role: string | null): boolean {
  if (!role) return false;
  return (
    required.includes(role) ||
    (DOCTOR_EQUIVALENT_ROLES.has(role) && required.includes('ROLE_DOCTOR'))
  );
}
