import { RoleResponse } from '../services/role.service';

/** Roles a non-super-admin may never grant (QA D189, D664). Bare codes. */
const ADMIN_ONLY_ROLES = new Set(['SUPER_ADMIN', 'ADMIN', 'HOSPITAL_ADMIN', 'PROVIDER_ADMIN']);

export function bareRoleCode(code: string | undefined | null): string {
  return (code ?? '').toUpperCase().replace(/^ROLE_/, '');
}

/**
 * The roles an admin form may offer (QA D626, D664): never the patient role,
 * for anyone (the server refuses it on admin-register and on /assignments; a
 * patient is registered from the patient form), and no admin role unless the
 * caller is a super-admin. Every identifier of a row is read, so a row whose
 * `code` is blank but whose name or authority names the role cannot slip through.
 */
export function grantableRoles(roles: RoleResponse[], isSuperAdmin: boolean): RoleResponse[] {
  const ids = (r: RoleResponse) => [r.code, r.name, r.authority].map(bareRoleCode);
  const nonPatient = roles.filter((r) => !ids(r).includes('PATIENT'));
  if (isSuperAdmin) return nonPatient;
  return nonPatient.filter((r) => !ids(r).some((id) => ADMIN_ONLY_ROLES.has(id)));
}
