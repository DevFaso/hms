import { Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

/**
 * QA D4318 (r81): the policies and rules the platform creates for every organization are
 * stored under their English names (« Default Password Policy »), and Gouvernance showed
 * them as stored on a French screen. A name the platform wrote is read in the reader's
 * language; a name a person typed is shown as typed.
 */
export const GOVERNANCE_DEFAULT_NAMES: Readonly<Record<string, string>> = {
  'Default Healthcare Organization': 'ADMIN_GOV.DEFAULT_NAME.DEFAULT_ORG',
  'Default Access Control Policy': 'ADMIN_GOV.DEFAULT_NAME.ACCESS_CONTROL',
  'Patient Data Access Rule': 'ADMIN_GOV.DEFAULT_NAME.PATIENT_DATA_ACCESS',
  'Admin Endpoint Access Rule': 'ADMIN_GOV.DEFAULT_NAME.ADMIN_ENDPOINT_ACCESS',
  'Default Data Protection Policy': 'ADMIN_GOV.DEFAULT_NAME.DATA_PROTECTION',
  'Default Audit Logging Policy': 'ADMIN_GOV.DEFAULT_NAME.AUDIT_LOGGING',
  'Audit Sensitive Operations': 'ADMIN_GOV.DEFAULT_NAME.AUDIT_SENSITIVE',
  'Default Password Policy': 'ADMIN_GOV.DEFAULT_NAME.PASSWORD_POLICY',
  'Password Minimum Length': 'ADMIN_GOV.DEFAULT_NAME.PASSWORD_MIN_LENGTH',
  'Default Session Management Policy': 'ADMIN_GOV.DEFAULT_NAME.SESSION_MANAGEMENT',
  'Session Timeout': 'ADMIN_GOV.DEFAULT_NAME.SESSION_TIMEOUT',
  'Default Role Management Policy': 'ADMIN_GOV.DEFAULT_NAME.ROLE_MANAGEMENT',
};

/** The translation key of a platform-written name, or null for a name a person typed. */
export function governanceNameKey(name: string | null | undefined): string | null {
  return GOVERNANCE_DEFAULT_NAMES[(name ?? '').trim()] ?? null;
}

/** `{{ policy.name | govName }}`. Impure, like the translate pipe: it follows a language switch. */
@Pipe({ name: 'govName', standalone: true, pure: false })
export class GovernanceNamePipe implements PipeTransform {
  private readonly translate = inject(TranslateService);

  transform(name: string | null | undefined): string {
    const key = governanceNameKey(name);
    return key ? this.translate.instant(key) : (name ?? '');
  }
}
