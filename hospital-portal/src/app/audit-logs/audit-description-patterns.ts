/**
 * QA D774 / D869 / D894: audit descriptions are stored as the server wrote
 * them, in English with codes and ids. Each pattern below reads one of them in
 * the reader's language; the parameters (a person, an id, a free-text reason)
 * are kept, and the ones that are codes are read through a domain.
 *
 * One entry per description the server writes. The server-side guard
 * (AuditDescriptionCoverageTest) builds every description the code can write
 * and fails when none of these patterns matches it, so a new audit
 * description needs its pattern here and its text in the three locales.
 * The first matching pattern wins: specific shapes come before general ones.
 * Every regex must also compile in Java (no flags, no lookbehind).
 */

/**
 * How a parameter is shown: verbatim (absent), a role, a lab approval code, a
 * scope refusal reason, any enum code ('enum'), or a fixed English phrase
 * looked up under AUDIT.<GROUP>.<PHRASE_IN_UPPER_SNAKE> ('phrase:GROUP').
 */
export type AuditParamDomain =
  | 'role'
  | 'labDefinitionAction'
  | 'labApprovalStatus'
  | 'scopeRefusal'
  | 'enum'
  | `phrase:${string}`;

export interface AuditDescriptionPattern {
  readonly key: string;
  readonly re: RegExp;
  /** The capture groups' names, in order. */
  readonly params?: readonly string[];
  readonly localise?: Readonly<Record<string, AuditParamDomain>>;
}

export const AUDIT_DESCRIPTION_PATTERNS: readonly AuditDescriptionPattern[] = [
  {
    key: 'AUDIT.DESC.DUPLICATE_KEPT_SEPARATE',
    re: /^Possible duplicate kept as a separate patient$/,
  },
  {
    key: 'AUDIT.DESC.NATIONAL_IU_ACTION',
    re: /^Patient identity: (.+)$/,
    params: ['operation'],
    localise: { operation: 'phrase:NATIONAL_IU_ACTION' },
  },
  {
    key: 'AUDIT.DESC.PATIENT_RECORD_VIEWED',
    re: /^Patient record viewed$/,
  },
  {
    key: 'AUDIT.DESC.HOSPITAL_SCOPE_REFUSED',
    re: /^Hospital scope refused \(([A-Z_]+)\)$/,
    params: ['reason'],
    localise: { reason: 'scopeRefusal' },
  },
  {
    key: 'AUDIT.DESC.USER_PROFILE_UPDATED',
    re: /^User profile updated$/,
  },
  {
    key: 'AUDIT.DESC.USER_CREATED',
    re: /^New user account created$/,
  },
  {
    key: 'AUDIT.DESC.USER_DELETED',
    re: /^User account soft-deleted$/,
  },
  {
    key: 'AUDIT.DESC.USER_RESTORED',
    re: /^User account restored$/,
  },
  {
    key: 'AUDIT.DESC.DOCTOR_RECORD_VIEW',
    re: /^Doctor record view$/,
  },
  {
    key: 'AUDIT.DESC.DOCTOR_TIMELINE_VIEW',
    re: /^Doctor timeline view$/,
  },
  {
    key: 'AUDIT.DESC.MFA_CHALLENGE_ISSUED',
    re: /^MFA challenge issued$/,
  },
  {
    key: 'AUDIT.DESC.TOTP_ENROLLMENT_VERIFIED',
    re: /^TOTP enrollment verified$/,
  },
  {
    key: 'AUDIT.DESC.FIRST_SIGN_IN_COMPLETED',
    re: /^First sign-in setup completed$/,
  },
  {
    key: 'AUDIT.DESC.ROLE_ASSIGNMENT_REFUSED',
    re: /^Role assignment refused: the caller may not grant this role there$/,
  },
  {
    key: 'AUDIT.DESC.ROLE_ASSIGNED',
    re: /^Assigned role '([^']+)' to (.+)$/,
    params: ['role', 'name'],
    localise: { role: 'role' },
  },
  {
    // QA D907: a staff deactivation switches off the person's roles there.
    key: 'AUDIT.DESC.ROLE_REVOKED_STAFF_DEACTIVATED',
    re: /^Revoked role '([^']+)' from (.+): staff record deactivated$/,
    params: ['role', 'name'],
    localise: { role: 'role' },
  },
  {
    key: 'AUDIT.DESC.ASSIGNMENT_CONFIRMED',
    re: /^Confirmed assignment '([^']+)' for (.+)$/,
    params: ['role', 'name'],
    localise: { role: 'role' },
  },
  {
    key: 'AUDIT.DESC.LAB_DEFINITION_STEP_SINGLE_DIRECTOR',
    re: /^Lab test definition (\S+): approval step (\S+), new status (.+?) \(the approver also completed the QA review\)\.$/,
    params: ['code', 'step', 'status'],
    localise: { step: 'labDefinitionAction', status: 'labApprovalStatus' },
  },
  // QA D905: rows written before #968 are in the approver's language, with
  // the status already a label ("Actif", "En attente de revue qualité"); the
  // labApprovalStatus domain reads those labels back as their code. The
  // single-director shapes come first, or the general ones below would take
  // the French or Spanish suffix as part of the status.
  {
    key: 'AUDIT.DESC.LAB_DEFINITION_STEP_SINGLE_DIRECTOR_FR',
    re: /^Examen de laboratoire (\S+) : étape d'approbation (\S+), nouveau statut (.+?) \(l'approbateur a aussi effectué la revue qualité\)\.$/,
    params: ['code', 'step', 'status'],
    localise: { step: 'labDefinitionAction', status: 'labApprovalStatus' },
  },
  {
    key: 'AUDIT.DESC.LAB_DEFINITION_STEP_SINGLE_DIRECTOR_ES',
    re: /^Prueba de laboratorio (\S+): paso de aprobación (\S+), nuevo estado (.+?) \(el aprobador también realizó la revisión de calidad\)\.$/,
    params: ['code', 'step', 'status'],
    localise: { step: 'labDefinitionAction', status: 'labApprovalStatus' },
  },
  {
    key: 'AUDIT.DESC.LAB_DEFINITION_STEP_FR',
    re: /^Examen de laboratoire (\S+) : étape d'approbation (\S+), nouveau statut (.+?)\.$/,
    params: ['code', 'step', 'status'],
    localise: { step: 'labDefinitionAction', status: 'labApprovalStatus' },
  },
  {
    key: 'AUDIT.DESC.LAB_DEFINITION_STEP_ES',
    re: /^Prueba de laboratorio (\S+): paso de aprobación (\S+), nuevo estado (.+?)\.$/,
    params: ['code', 'step', 'status'],
    localise: { step: 'labDefinitionAction', status: 'labApprovalStatus' },
  },
  {
    key: 'AUDIT.DESC.LAB_SELF_RELEASE',
    re: /^(?:Self-release: the result was released by the user who entered it\.|Autovalidation : le résultat a été libéré par la personne qui l'a saisi\.|Autovalidación: el resultado fue liberado por la persona que lo registró\.)$/,
  },
  {
    key: 'AUDIT.DESC.FHIR_EVERYTHING',
    re: /^FHIR Patient\/(\S+)\/\$everything returned a (\S+)-entry Bundle(.*)$/,
    params: ['patient', 'count', 'details'],
  },
  {
    key: 'AUDIT.DESC.LAB_DEFINITION_STEP',
    re: /^Lab test definition (\S+): approval step (\S+), new status (.+?)\.$/,
    params: ['code', 'step', 'status'],
    localise: { step: 'labDefinitionAction', status: 'labApprovalStatus' },
  },
  // QA D905: the shape before QA D638 named neither the test nor a label.
  {
    key: 'AUDIT.DESC.LAB_DEFINITION_STEP_LEGACY_SINGLE_DIRECTOR',
    re: /^Lab test definition approval step (\S+) -> (\S+) \(the approver also completed the QA review\)$/,
    params: ['step', 'status'],
    localise: { step: 'labDefinitionAction', status: 'labApprovalStatus' },
  },
  {
    key: 'AUDIT.DESC.LAB_DEFINITION_STEP_LEGACY',
    re: /^Lab test definition approval step (\S+) -> (\S+)$/,
    params: ['step', 'status'],
    localise: { step: 'labDefinitionAction', status: 'labApprovalStatus' },
  },
  {
    key: 'AUDIT.DESC.LOGIN_FAILED_BAD_CREDENTIALS',
    re: /^Login failed: bad credentials$/,
  },
  {
    key: 'AUDIT.DESC.LOGIN_FAILED_INACTIVE',
    re: /^Login failed: account not active$/,
  },
  {
    key: 'AUDIT.DESC.LOGIN_REFUSED_NO_ROLE',
    re: /^Login refused: no active role$/,
  },
  {
    key: 'AUDIT.DESC.LOGIN_REFUSED_TEMP_PASSWORD_EXPIRED',
    re: /^Login refused: temporary password expired$/,
  },
  {
    key: 'AUDIT.DESC.LOGIN_REJECTED_LOCKED',
    re: /^Login rejected — account locked$/,
  },
  {
    key: 'AUDIT.DESC.FIRST_USER_BOOTSTRAP',
    re: /^First system user bootstrap \(Super Admin\)$/,
  },
  {
    key: 'AUDIT.DESC.ACCESS_TOKEN_REVOKED',
    re: /^Access token revoked on logout \(jti=([^)]*)\)$/,
    params: ['jti'],
  },
  {
    key: 'AUDIT.DESC.REFRESH_TOKEN_REVOKED',
    re: /^Refresh token revoked on logout \(jti=([^)]*)\)$/,
    params: ['jti'],
  },
  // D2001: the lock screen counts in the sign-in's attempt bucket and ends the
  // session at the lockout. The jti is matched but never shown.
  {
    key: 'AUDIT.DESC.LOCK_SCREEN_UNLOCK_FAILED',
    re: /^Lock-screen unlock failed: bad credentials$/,
  },
  {
    key: 'AUDIT.DESC.LOCK_SCREEN_LOCKED_NOW',
    re: /^Lock-screen unlock failed too many times: account locked; session ended$/,
  },
  {
    key: 'AUDIT.DESC.LOCK_SCREEN_REFUSED_LOCKED',
    re: /^Lock-screen unlock refused: account locked; session ended$/,
  },
  // QA D2020: a refused current password on a password change or the forced setup.
  {
    key: 'AUDIT.DESC.PASSWORD_CHANGE_WRONG_CURRENT',
    re: /^Password change refused: current password incorrect$/,
  },
  {
    key: 'AUDIT.DESC.PASSWORD_CHANGE_LOCKED_NOW',
    re: /^Password change failed too many times: account locked$/,
  },
  {
    key: 'AUDIT.DESC.PASSWORD_CHANGE_REFUSED_LOCKED',
    re: /^Password change refused: account locked$/,
  },
  {
    key: 'AUDIT.DESC.ACCOUNT_SETUP_WRONG_CURRENT',
    re: /^Account setup refused: current password incorrect$/,
  },
  {
    key: 'AUDIT.DESC.ACCOUNT_SETUP_LOCKED_NOW',
    re: /^Account setup failed too many times: account locked$/,
  },
  {
    key: 'AUDIT.DESC.ACCOUNT_SETUP_REFUSED_LOCKED',
    re: /^Account setup refused: account locked$/,
  },
  {
    key: 'AUDIT.DESC.ACCESS_TOKEN_REVOKED_LOCKOUT',
    re: /^Access token revoked on lock-screen lockout \(jti=[^)]*\)$/,
  },
  {
    key: 'AUDIT.DESC.REFRESH_TOKEN_REVOKED_LOCKOUT',
    re: /^Refresh token revoked on lock-screen lockout \(jti=[^)]*\)$/,
  },
  // QA D2184: logout and the lock-screen lockout end the whole sign-in session.
  // The sid is matched but never shown.
  {
    key: 'AUDIT.DESC.SESSION_REVOKED',
    re: /^Session revoked on logout \(sid=[^)]*\)$/,
  },
  {
    key: 'AUDIT.DESC.SESSION_REVOKED_LOCKOUT',
    re: /^Session revoked on lock-screen lockout \(sid=[^)]*\)$/,
  },
  {
    key: 'AUDIT.DESC.TOKEN_PAIR_ROTATED',
    re: /^Token pair rotated \(old refresh jti=([^)]*)\)$/,
    params: ['jti'],
  },
  {
    key: 'AUDIT.DESC.REFRESH_REFUSED_IDLE',
    re: /^Refresh refused — idle timeout$/,
  },
  {
    key: 'AUDIT.DESC.REFRESH_REFUSED_SECOND_FACTOR',
    // Rows written before the rule covered every MFA-required role say "provider".
    re: /^Refresh refused — (?:provider )?second factor missing or expired$/,
  },
  {
    key: 'AUDIT.DESC.REFRESH_REFUSED_ROLES_GONE',
    re: /^Refresh refused — the session's roles are no longer held$/,
  },
  {
    key: 'AUDIT.DESC.REFRESH_REFUSED_CUTOFF',
    re: /^Refresh refused — issued before the sign-in cutoff$/,
  },
  // Sessions end a fixed time after sign-in; a reused refresh token ends the session.
  {
    key: 'AUDIT.DESC.REFRESH_REFUSED_LIFETIME',
    re: /^Refresh refused — the session reached its maximum lifetime$/,
  },
  {
    key: 'AUDIT.DESC.REFRESH_REFUSED_REUSED',
    re: /^Refresh refused — a used refresh token was presented again \(jti=[^)]*\); its session was ended$/,
  },
  {
    key: 'AUDIT.DESC.SESSION_REVOKED_REFRESH_REUSE',
    re: /^Session revoked on refresh token reuse \(sid=[^)]*\)$/,
  },
  {
    key: 'AUDIT.DESC.SESSION_REVOKED_SECOND_FACTOR',
    re: /^Session revoked on second-factor lapse \(sid=[^)]*\)$/,
  },
  {
    key: 'AUDIT.DESC.PASSWORD_REUSE_REJECTED',
    re: /^Password change rejected — matches a recent password$/,
  },
  {
    key: 'AUDIT.DESC.PASSWORD_RESET_COMPLETED',
    re: /^Password reset completed$/,
  },
  {
    key: 'AUDIT.DESC.PASSWORD_RESET_LINK_SMS',
    re: /^Password reset link issued by SMS$/,
  },
  {
    key: 'AUDIT.DESC.PASSWORD_RESET_LINK',
    re: /^Password reset link issued$/,
  },
  {
    key: 'AUDIT.DESC.OTP_DISPATCHED',
    re: /^OTP dispatched to (.+)$/,
    params: ['phone'],
  },
  {
    key: 'AUDIT.DESC.OTP_CONFIRMED',
    re: /^OTP confirmed for (.+)$/,
    params: ['phone'],
  },
  {
    key: 'AUDIT.DESC.MFA_SIGNIN_ROLE_LOST',
    re: /^MFA sign-in refused: the role picked at login is no longer held$/,
  },
  {
    key: 'AUDIT.DESC.MFA_SIGNIN_NO_ROLE',
    re: /^MFA sign-in refused: no active role$/,
  },
  {
    key: 'AUDIT.DESC.TOTP_ENROLLMENT_STARTED',
    re: /^TOTP enrollment started$/,
  },
  {
    key: 'AUDIT.DESC.TOTP_REPLACEMENT_STARTED',
    re: /^TOTP replacement started \(pending until confirmed\)$/,
  },
  {
    key: 'AUDIT.DESC.TOTP_ENROLLMENT_INVALID_CODE',
    re: /^Invalid TOTP code during enrollment verification$/,
  },
  {
    key: 'AUDIT.DESC.MFA_TOO_MANY_CODES',
    re: /^MFA code refused during login: too many wrong codes$/,
  },
  {
    key: 'AUDIT.DESC.MFA_INVALID_CODE',
    re: /^Invalid MFA code during login$/,
  },
  {
    key: 'AUDIT.DESC.MFA_VERIFIED_TOTP',
    re: /^MFA verified via TOTP$/,
  },
  {
    key: 'AUDIT.DESC.MFA_VERIFIED_BACKUP',
    re: /^MFA verified via backup code$/,
  },
  {
    key: 'AUDIT.DESC.MFA_STEP_UP_REFUSED',
    re: /^MFA step-up for (.+) refused: mfa\.stepup\.(\w+)$/,
    params: ['action', 'reason'],
    localise: { action: 'phrase:MFA_ACTION', reason: 'phrase:MFA_REFUSAL' },
  },
  {
    key: 'AUDIT.DESC.MFA_STEP_UP_REFUSED_OTHER',
    re: /^MFA step-up for (.+) refused: (.+)$/,
    params: ['action', 'reason'],
    localise: { action: 'phrase:MFA_ACTION' },
  },
  {
    key: 'AUDIT.DESC.NO_STEP_UP_EMERGENCY',
    re: /^Emergency (\S+) performed without MFA step-up \(actor not enrolled, non-strict mode\)\.$/,
    params: ['action'],
    localise: { action: 'phrase:EMERGENCY_ACTION' },
  },
  {
    key: 'AUDIT.DESC.NO_STEP_UP_HOSPITAL',
    re: /^Destructive hospital-lifecycle action '([^']+)' performed without MFA step-up \(actor not enrolled, non-strict mode\)\.$/,
    params: ['action'],
    localise: { action: 'phrase:LIFECYCLE_ACTION' },
  },
  {
    key: 'AUDIT.DESC.NO_STEP_UP_TENANT',
    re: /^Destructive tenant-lifecycle action '([^']+)' performed without MFA step-up \(actor not enrolled, non-strict mode\)\.$/,
    params: ['action'],
    localise: { action: 'phrase:LIFECYCLE_ACTION' },
  },
  {
    key: 'AUDIT.DESC.NO_STEP_UP_IMPERSONATION',
    re: /^Impersonation start performed without MFA step-up \(actor not enrolled, non-strict mode\)\.$/,
  },
  {
    key: 'AUDIT.DESC.NO_STEP_UP_MFA_CHANGE',
    re: /^MFA enrolment change performed without MFA step-up \(actor not enrolled, non-strict mode\)\.$/,
  },
  {
    key: 'AUDIT.DESC.EMERGENCY_BROADCAST_SENT',
    re: /^(EMERGENCY_[A-Z_]+) — Broadcast \(([A-Z]+)\): (.*)$/,
    params: ['action', 'severity', 'message'],
    localise: { action: 'enum', severity: 'enum' },
  },
  {
    key: 'AUDIT.DESC.EMERGENCY_MFA_CLEARED_SCOPED',
    re: /^(EMERGENCY_[A-Z_]+) — Cleared MFA for (\S+) user\(s\) \(scoped to hospital ([^)]+)\): (.*)$/,
    params: ['action', 'count', 'hospital', 'reason'],
    localise: { action: 'enum' },
  },
  {
    key: 'AUDIT.DESC.EMERGENCY_MFA_CLEARED',
    re: /^(EMERGENCY_[A-Z_]+) — Cleared MFA for (\S+) user\(s\): (.*)$/,
    params: ['action', 'count', 'reason'],
    localise: { action: 'enum' },
  },
  {
    key: 'AUDIT.DESC.EMERGENCY_FLAG_KILLED',
    re: /^(EMERGENCY_[A-Z_]+) — Killed feature flag '([^']*)': (.*)$/,
    params: ['action', 'flag', 'reason'],
    localise: { action: 'enum' },
  },
  {
    key: 'AUDIT.DESC.EMERGENCY_ACTION',
    re: /^(EMERGENCY_[A-Z_]+) — (.*)$/,
    params: ['action', 'reason'],
    localise: { action: 'enum' },
  },
  {
    key: 'AUDIT.DESC.READ_ONLY_ON_REASON',
    re: /^Platform read-only mode ACTIVATED: (.*)$/,
    params: ['reason'],
  },
  {
    key: 'AUDIT.DESC.READ_ONLY_ON',
    re: /^Platform read-only mode ACTIVATED$/,
  },
  {
    key: 'AUDIT.DESC.READ_ONLY_OFF_REASON',
    re: /^Platform read-only mode deactivated: (.*)$/,
    params: ['reason'],
  },
  {
    key: 'AUDIT.DESC.READ_ONLY_OFF',
    re: /^Platform read-only mode deactivated$/,
  },
  {
    key: 'AUDIT.DESC.FLAG_OVERRIDE_DELETED',
    re: /^Feature flag override deleted key=(\S*)(.*) previousEnabled=(\S*)$/,
    params: ['key', 'scope', 'previous'],
  },
  {
    key: 'AUDIT.DESC.FLAG_OVERRIDE',
    re: /^Feature flag override (\S+) key=(\S*) enabled=(\S*)(.*) previousEnabled=(\S*)$/,
    params: ['verb', 'key', 'enabled', 'scope', 'previous'],
    localise: { verb: 'phrase:FLAG_VERB' },
  },
  {
    key: 'AUDIT.DESC.PLAN_TIER_BLOCKED',
    re: /^Plan-tier gate blocked flag '([^']*)' for organization (\S+)$/,
    params: ['flag', 'org'],
  },
  {
    key: 'AUDIT.DESC.API_KEY_EVENT',
    re: /^API key (issued|revoked|rotated) \((.*)\)$/,
    params: ['verb', 'prefix'],
    localise: { verb: 'phrase:API_KEY_VERB' },
  },
  {
    key: 'AUDIT.DESC.RELEASE_WINDOW',
    re: /^Release window scheduled (.*)$/,
    params: ['details'],
  },
  {
    key: 'AUDIT.DESC.REGION_POLICY_UPDATED',
    re: /^Region (\S+) policy updated: (.*)$/,
    params: ['region', 'details'],
  },
  {
    key: 'AUDIT.DESC.ORG_REGION_CHANGED_REASON',
    re: /^Organization region changed from (\S+) to (\S+): (.*)$/,
    params: ['from', 'to', 'reason'],
  },
  {
    key: 'AUDIT.DESC.ORG_REGION_CHANGED',
    re: /^Organization region changed from (\S+) to (\S+)$/,
    params: ['from', 'to'],
  },
  {
    key: 'AUDIT.DESC.ORG_REGION_REAFFIRMED_REASON',
    re: /^Organization region reaffirmed at (\S+): (.*)$/,
    params: ['region', 'reason'],
  },
  {
    key: 'AUDIT.DESC.ORG_REGION_REAFFIRMED',
    re: /^Organization region reaffirmed at (\S+)$/,
    params: ['region'],
  },
  {
    key: 'AUDIT.DESC.PLATFORM_SERVICE_REGISTERED',
    re: /^Platform service registered (type=.*)$/,
    params: ['details'],
  },
  {
    key: 'AUDIT.DESC.PLATFORM_SERVICE_UPDATED',
    re: /^Platform service updated (type=.*)$/,
    params: ['details'],
  },
  {
    key: 'AUDIT.DESC.PLATFORM_SERVICE_STATUS',
    re: /^Platform service status set to (\S+) (type=.*)$/,
    params: ['status', 'details'],
    localise: { status: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PLATFORM_LINK_TOGGLED',
    re: /^Platform (hospital|department) link (enabled|disabled) (type=.*)$/,
    params: ['target', 'state', 'details'],
    localise: { target: 'phrase:PLATFORM_TARGET', state: 'phrase:PLATFORM_LINK_STATE' },
  },
  {
    key: 'AUDIT.DESC.PLATFORM_SERVICE_LINKED',
    re: /^Platform (hospital|department) linked \(enabled=(\S+)\) (type=.*)$/,
    params: ['target', 'enabled', 'details'],
    localise: { target: 'phrase:PLATFORM_TARGET' },
  },
  {
    key: 'AUDIT.DESC.PLATFORM_SERVICE_UNLINKED',
    re: /^Platform (hospital|department) unlinked (type=.*)$/,
    params: ['target', 'details'],
    localise: { target: 'phrase:PLATFORM_TARGET' },
  },
  {
    key: 'AUDIT.DESC.SCHEMA_PROVISIONED',
    re: /^Schema provisioned for hospital (\S+) \((.*)\)$/,
    params: ['hospital', 'details'],
  },
  {
    key: 'AUDIT.DESC.SCHEMA_CACHE_INVALIDATED',
    re: /^TenantSchemaLookup cache entry invalidated for hospital (\S+) \(.*\)$/,
    params: ['hospital'],
  },
  {
    key: 'AUDIT.DESC.TENANT_ARCHIVE_PACKAGED',
    re: /^Tenant archive packaged \+ encrypted for organization (\S+) at (\S+) \((.*)\)$/,
    params: ['org', 'path', 'details'],
  },
  {
    key: 'AUDIT.DESC.TENANT_PURGE_EXECUTED',
    re: /^Scheduled purge executed for organization (\S+); archive at (.+)$/,
    params: ['org', 'path'],
  },
  {
    key: 'AUDIT.DESC.TENANT_PACKAGING_FAILED',
    re: /^Packaging failed for organization (\S+): (.*)$/,
    params: ['org', 'error'],
  },
  {
    key: 'AUDIT.DESC.WEBHOOK_AUTO_DISABLED',
    re: /^Webhook endpoint auto-disabled after (\S+) consecutive delivery failures$/,
    params: ['count'],
  },
  {
    key: 'AUDIT.DESC.WEBHOOK_SECRET_ROTATED',
    re: /^Webhook signing secret rotated$/,
  },
  {
    key: 'AUDIT.DESC.WEBHOOK_ENDPOINT',
    re: /^Webhook endpoint (registered|updated|paused|resumed|revoked)$/,
    params: ['verb'],
    localise: { verb: 'phrase:WEBHOOK_VERB' },
  },
  {
    key: 'AUDIT.DESC.GLOBAL_VIEW_REQUEST',
    re: /^Super-admin global-view request: (\S+) (.+)$/,
    params: ['method', 'path'],
  },
  {
    key: 'AUDIT.DESC.CROSS_TENANT_SEARCH',
    re: /^Super-admin cross-tenant read: hospitals\/search\?q=(.*) \((\S+) rows\)$/,
    params: ['query', 'rows'],
  },
  {
    key: 'AUDIT.DESC.CROSS_TENANT_READ',
    re: /^Super-admin cross-tenant read: (.+) \((\S+) rows\)$/,
    params: ['view', 'rows'],
    localise: { view: 'phrase:CROSS_TENANT_VIEW' },
  },
  {
    key: 'AUDIT.DESC.CROSS_HOSPITAL_READ_LAB',
    re: /^Cross-hospital lab order read at the performing laboratory$/,
  },
  {
    key: 'AUDIT.DESC.CROSS_HOSPITAL_READ_LAB_RESULT',
    re: /^Cross-hospital lab result read at the performing laboratory$/,
  },
  {
    // Owner decision 2026-11-22: another hospital's policy, identity only, read to verify or taken over.
    key: 'AUDIT.DESC.CROSS_HOSPITAL_INSURANCE_READ',
    re: /^Cross-hospital insurance policy identity read to verify at the desk$/,
  },
  {
    key: 'AUDIT.DESC.CROSS_HOSPITAL_INSURANCE_REUSED',
    re: /^Cross-hospital insurance policy identity reused into this hospital's own coverage$/,
  },
  {
    // QA D3351 (owner decision 2026-11-22): the allo-immunisation flag follows the patient across hospitals.
    key: 'AUDIT.DESC.CROSS_HOSPITAL_ANTIBODY_FLAG',
    re: /^Cross-hospital allo-immunisation flag read \(transfusion safety, shown whatever the sharing choice\)$/,
  },
  {
    // V380 (owner decision 2026-11-25): the RhD positive exposure flag follows the patient across hospitals.
    key: 'AUDIT.DESC.CROSS_HOSPITAL_RHD_EXPOSURE_FLAG',
    re: /^Cross-hospital RhD positive exposure flag read \(transfusion safety, shown whatever the sharing choice\)$/,
  },
  {
    // V380: the decision behind an emergency O positive release; the tier and the basis are in the details.
    key: 'AUDIT.DESC.O_POSITIVE_RELEASE_DECIDED',
    re: /^Emergency O positive release decision recorded for blood unit (\S+) against transfusion request (\S+)$/,
    params: ['unit', 'request'],
  },
  {
    key: 'AUDIT.DESC.O_POSITIVE_RELEASE_COUNTERSIGNED',
    re: /^Emergency O positive release countersigned: decision (\S+)$/,
    params: ['decision'],
  },
  {
    key: 'AUDIT.DESC.O_POSITIVE_RELEASE_REVIEWED',
    re: /^Emergency O positive release reviewed by the biologist: decision (\S+)$/,
    params: ['decision'],
  },
  {
    key: 'AUDIT.DESC.O_POSITIVE_FOLLOW_UP_RECORDED',
    re: /^RhD positive exposure follow-up recorded: decision (\S+)$/,
    params: ['decision'],
  },
  {
    key: 'AUDIT.DESC.O_POSITIVE_COUNTERSIGN_OVERDUE',
    re: /^Emergency O positive release countersignature overdue, escalated to the laboratory director: decision (\S+)$/,
    params: ['decision'],
  },
  {
    // V380: a hospital's emergency O positive protocol changed (the values are in the details).
    key: 'AUDIT.DESC.O_POSITIVE_PROTOCOL_CHANGED',
    re: /^Emergency O positive release protocol changed$/,
  },
  {
    key: 'AUDIT.DESC.CROSS_HOSPITAL_READ',
    re: /^Cross-hospital (.+) read on the treatment relationship(.*)$/,
    params: ['subject', 'extra'],
    localise: { subject: 'phrase:CROSS_HOSPITAL_SUBJECT', extra: 'phrase:CROSS_HOSPITAL_PURPOSE' },
  },
  {
    // Billing-money plan PR 8 review: a support session never moves money as the user it impersonates.
    key: 'AUDIT.DESC.SUPPORT_SESSION_MONEY_REFUSED',
    re: /^Support session refused: (.+) \(money\)$/,
    params: ['what'],
  },
  {
    // V356: a support session never signs off or revokes a hospital's provisional rules in the admin's name.
    key: 'AUDIT.DESC.SUPPORT_SESSION_RULE_SIGNOFF_REFUSED',
    re: /^Support session refused: (.+) \(clinical-rule sign-off\)$/,
    params: ['what'],
  },
  {
    // Billing-money plan PR 8 review: a cancelled dispense's payment voided (ids only; older rows below).
    key: 'AUDIT.DESC.PHARMACY_PAYMENT_VOIDED_IDS',
    re: /^Pharmacy payment (\S+) voided for cancelled dispense (\S+)$/,
    params: ['payment', 'dispense'],
  },
  {
    key: 'AUDIT.DESC.SUPPORT_SESSION_REFUSED',
    re: /^Support session refused: (.+) \(sign-in or recovery details\)$/,
    params: ['what'],
  },
  {
    // QA D2120 review: ClinicalActorAuditRegistrar, a clinical act tried during a support session.
    key: 'AUDIT.DESC.SUPPORT_SESSION_CLINICAL_REFUSED',
    re: /^Support session refused: a clinical act \((.*)\) during impersonation$/,
    params: ['roles'],
  },
  {
    key: 'AUDIT.DESC.IMPERSONATION_STARTED',
    re: /^Started impersonating (.+)\. Reason: (.*)$/,
    params: ['user', 'reason'],
  },
  {
    key: 'AUDIT.DESC.IMPERSONATION_ENDED',
    re: /^Ended impersonation of (.+)$/,
    params: ['user'],
  },
  {
    key: 'AUDIT.DESC.USER_ACTION_REFUSED',
    re: /^User (update|delete|restore) refused: the caller may not administer this account$/,
    params: ['action'],
    localise: { action: 'phrase:USER_ACTION' },
  },
  {
    key: 'AUDIT.DESC.USER_REGISTRATION_REFUSED',
    re: /^User registration refused: the caller may not grant these roles$/,
  },
  {
    key: 'AUDIT.DESC.OWN_EMAIL_CHANGED',
    re: /^Own email address changed$/,
  },
  {
    key: 'AUDIT.DESC.OWN_EMAIL_REQUESTED',
    re: /^Own email change requested: a code was sent to the new address$/,
  },
  {
    key: 'AUDIT.DESC.OWN_EMAIL_TAKEN_ANSWERED',
    re: /^Own email change asked for an address that belongs to another account: answered as a request, and the holder was notified instead$/,
  },
  {
    key: 'AUDIT.DESC.OWN_EMAIL_REFUSED',
    re: /^Own email change refused: (.+)$/,
    params: ['reason'],
    localise: { reason: 'phrase:OWN_EMAIL_REFUSAL' },
  },
  {
    key: 'AUDIT.DESC.HOSPITAL_SUSPENDED',
    re: /^Hospital suspended: (.*)$/,
    params: ['reason'],
  },
  {
    key: 'AUDIT.DESC.HOSPITAL_ARCHIVED',
    re: /^Hospital archived: (.*)$/,
    params: ['reason'],
  },
  {
    key: 'AUDIT.DESC.HOSPITAL_RESTORED_REASON',
    re: /^Hospital restored from (\S+) to (\S+): (.*)$/,
    params: ['from', 'to', 'reason'],
    localise: { from: 'enum', to: 'enum' },
  },
  {
    key: 'AUDIT.DESC.HOSPITAL_RESTORED',
    re: /^Hospital restored from (\S+) to (\S+)$/,
    params: ['from', 'to'],
    localise: { from: 'enum', to: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PURGE_SCHEDULED',
    re: /^Purge scheduled for (\S+): (.*)$/,
    params: ['date', 'reason'],
  },
  {
    key: 'AUDIT.DESC.PURGE_CANCELLED_REASON',
    re: /^Purge cancelled: (.*)$/,
    params: ['reason'],
  },
  {
    key: 'AUDIT.DESC.PURGE_CANCELLED',
    re: /^Purge cancelled$/,
  },
  {
    key: 'AUDIT.DESC.ORG_SUSPENDED',
    re: /^Organization suspended: (.*)$/,
    params: ['reason'],
  },
  {
    key: 'AUDIT.DESC.ORG_ARCHIVED',
    re: /^Organization archived: (.*)$/,
    params: ['reason'],
  },
  {
    key: 'AUDIT.DESC.ORG_RESTORED_REASON',
    re: /^Organization restored from (\S+): (.*)$/,
    params: ['from', 'reason'],
    localise: { from: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ORG_RESTORED',
    re: /^Organization restored from (\S+)$/,
    params: ['from'],
    localise: { from: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PROVIDER_VERIFICATION',
    re: /^Provider facility (\S+) verification (\S+)$/,
    params: ['type', 'status'],
    localise: { type: 'enum', status: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PACS_SETTINGS_CHANGED',
    re: /^PACS settings changed$/,
  },
  {
    key: 'AUDIT.DESC.IMAGING_STUDY_VIEWED',
    re: /^Imaging study opened in the viewer \((\d+) instances\)$/,
    params: ['count'],
  },
  {
    key: 'AUDIT.DESC.RECORD_ACCESS_POSTURE',
    re: /^Record-access posture changed from (\S+) to (\S+)$/,
    params: ['from', 'to'],
    localise: { from: 'enum', to: 'enum' },
  },
  {
    key: 'AUDIT.DESC.SENSITIVITY_CHANGED',
    re: /^Sensitivity category changed from (\S+) to (\S+)$/,
    params: ['from', 'to'],
    localise: { from: 'enum', to: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PATIENT_LINKED',
    re: /^Patient linked to hospital '(.*)'$/,
    params: ['hospital'],
  },
  // QA D926: the desk's demographics corrections (PATCH names the fields, PUT replaces all).
  {
    key: 'AUDIT.DESC.PATIENT_DEMOGRAPHICS_UPDATED',
    re: /^Patient demographics updated: (.+)$/,
    params: ['fields'],
  },
  {
    key: 'AUDIT.DESC.PATIENT_DEMOGRAPHICS_REPLACED',
    re: /^Patient demographics replaced$/,
  },
  {
    key: 'AUDIT.DESC.PATIENT_ADDRESS_CHANGED',
    re: /^Patient address changed — previous address kept as history row (\S+)$/,
    params: ['row'],
  },
  {
    key: 'AUDIT.DESC.SHARING_OPTED_OUT',
    re: /^Patient opted out of cross-hospital record sharing$/,
  },
  {
    key: 'AUDIT.DESC.SHARING_OPT_OUT_REVOKED',
    re: /^Patient's cross-hospital record sharing opt-out revoked$/,
  },
  {
    key: 'AUDIT.DESC.ROI_PDF_DOWNLOADED',
    re: /^Patient record PDF downloaded for Patient\/(\S+) at hospital (\S+) \((\S+) chart entries\) under ROI request (\S+)$/,
    params: ['patient', 'hospital', 'entries', 'request'],
  },
  {
    key: 'AUDIT.DESC.PATIENT_PDF_DOWNLOADED',
    re: /^Patient record PDF downloaded for Patient\/(\S+) at hospital (\S+) \((\S+) chart entries\)$/,
    params: ['patient', 'hospital', 'entries'],
  },
  {
    key: 'AUDIT.DESC.EMPI_MERGE',
    re: /^EMPI merge: (\S+) merged into (\S+) \((.*)\)$/,
    params: ['secondary', 'primary', 'details'],
  },
  {
    key: 'AUDIT.DESC.BREAK_GLASS_READ_NO_PURPOSE',
    re: /^BREAK_GLASS read: \(no purpose given\)$/,
  },
  {
    key: 'AUDIT.DESC.BREAK_GLASS_READ',
    re: /^BREAK_GLASS read: (.*)$/,
    params: ['what'],
  },
  {
    key: 'AUDIT.DESC.BREAK_GLASS_DECLARED',
    re: /^Break-the-glass session declared \(TTL (\S+) min\): (.*)$/,
    params: ['minutes', 'reason'],
  },
  {
    key: 'AUDIT.DESC.BREAK_GLASS_REVIEWED',
    re: /^Break-the-glass session reviewed: (.*)$/,
    params: ['outcome'],
  },
  {
    key: 'AUDIT.DESC.BREAK_GLASS_REVOKED_NO_REASON',
    re: /^Break-the-glass session revoked early: no reason given$/,
  },
  {
    key: 'AUDIT.DESC.BREAK_GLASS_REVOKED',
    re: /^Break-the-glass session revoked early: (.*)$/,
    params: ['reason'],
  },
  {
    key: 'AUDIT.DESC.STAFF_DOCUMENT_LIST_BG',
    re: /^Staff list of patient-uploaded documents under break-the-glass session (\S+)$/,
    params: ['session'],
  },
  {
    key: 'AUDIT.DESC.STAFF_DOCUMENT_LIST',
    re: /^Staff list of patient-uploaded documents$/,
  },
  {
    key: 'AUDIT.DESC.STAFF_DOCUMENT_READ_BG',
    re: /^Staff (view|download) of patient-uploaded document (\S+) \(([^)]*)\) under break-the-glass session (\S+)$/,
    params: ['verb', 'document', 'type', 'session'],
    localise: { verb: 'phrase:DOCUMENT_VERB', type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.STAFF_DOCUMENT_READ',
    re: /^Staff (view|download) of patient-uploaded document (\S+) \(([^)]*)\)$/,
    params: ['verb', 'document', 'type'],
    localise: { verb: 'phrase:DOCUMENT_VERB', type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ROI_RELEASED',
    re: /^Record copy released under ROI request (\S+)$/,
    params: ['request'],
  },
  {
    key: 'AUDIT.DESC.ROI_LOGGED',
    re: /^ROI request logged \((.*)\)$/,
    params: ['type'],
    localise: { type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ROI_STATUS',
    re: /^ROI request (fulfilled|denied|cancelled)$/,
    params: ['verb'],
    localise: { verb: 'phrase:ROI_VERB' },
  },
  {
    key: 'AUDIT.DESC.ENCOUNTER_STATUS_DENIED',
    re: /^Encounter status update DENIED — encounter not assigned to caller$/,
  },
  {
    key: 'AUDIT.DESC.ENCOUNTER_STATUS_UPDATED',
    re: /^Encounter status updated from (\S+) to (\S+)$/,
    params: ['from', 'to'],
    localise: { from: 'enum', to: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ENCOUNTER_DISCHARGE_COMPLETED',
    re: /^Encounter completed on discharge of admission (\S+) \(was (\S+)\)$/,
    params: ['admission', 'previous'],
    localise: { previous: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ENCOUNTER_CANCELLED_WITH_ADMISSION',
    re: /^Encounter cancelled with admission (\S+) \(was (\S+)\)$/,
    params: ['admission', 'previous'],
    localise: { previous: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ENCOUNTER_ADMISSION_COMPLETED',
    re: /^Encounter completed on admission (\S+) \(was (\S+)\)$/,
    params: ['admission', 'previous'],
    localise: { previous: 'enum' },
  },
  {
    key: 'AUDIT.DESC.CHECKED_IN',
    re: /^Patient checked in for appointment (\S+)\. Encounter (\S+) created with status ARRIVED\.( Identity confirmed\.)?( Insurance verified\.)?$/,
    params: ['appointment', 'encounter', 'identity', 'insurance'],
    localise: { identity: 'phrase:CHECK_IN', insurance: 'phrase:CHECK_IN' },
  },
  {
    key: 'AUDIT.DESC.REFERRAL_TRANSITION',
    re: /^Referral (\S+): (\S+) -> (\S+)$/,
    params: ['step', 'from', 'to'],
    localise: { step: 'phrase:REFERRAL_STEP', from: 'enum', to: 'enum' },
  },
  {
    key: 'AUDIT.DESC.REFERRAL_CREATED',
    re: /^Referral (\S+) -> (\S+)$/,
    params: ['step', 'to'],
    localise: { step: 'phrase:REFERRAL_STEP', to: 'enum' },
  },
  {
    key: 'AUDIT.DESC.CARE_GAP_RECALL',
    re: /^Care-gap sweep created a tracing recall for (\S+) \(visit expected (\S+)\)$/,
    params: ['programme', 'date'],
    localise: { programme: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PROGRAMME_ENROLLED',
    re: /^Enrolled in (\S+) \(cadence (\S+)d, from (\S+)\)$/,
    params: ['programme', 'days', 'date'],
    localise: { programme: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PROGRAMME_STATUS',
    re: /^(\S+) enrolment (\S+) -> (\S+)$/,
    params: ['programme', 'from', 'to'],
    localise: { programme: 'enum', from: 'enum', to: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PROGRAMME_VISIT',
    re: /^(\S+) visit on (\S+), next expected (\S+)$/,
    params: ['programme', 'date', 'next'],
    localise: { programme: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PANEL_EMPANELED',
    re: /^Empaneled as (\S+) \(from (\S+)\)$/,
    params: ['role', 'date'],
    localise: { role: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PANEL_SUPERSEDED',
    re: /^(\S+) empanelment superseded by reassignment \(ended (\S+)\)$/,
    params: ['role', 'date'],
    localise: { role: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PANEL_ENDED',
    re: /^(\S+) empanelment ended$/,
    params: ['role'],
    localise: { role: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ELIGIBILITY_CHECK_DETAILS',
    re: /^Eligibility (\S+) against scheme (\S+) — status=(\S+) \((.*)\)$/,
    params: ['type', 'scheme', 'status', 'details'],
    localise: { type: 'enum', status: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ELIGIBILITY_CHECK',
    re: /^Eligibility (\S+) against scheme (\S+) — status=(\S+)$/,
    params: ['type', 'scheme', 'status'],
    localise: { type: 'enum', status: 'enum' },
  },
  {
    key: 'AUDIT.DESC.NURSING_NOTE',
    re: /^Nursing note documented$/,
  },
  {
    key: 'AUDIT.DESC.NURSING_NOTE_ADDENDUM',
    re: /^Addendum added to nursing note$/,
  },
  {
    key: 'AUDIT.DESC.PRO_INSTRUMENT',
    re: /^PRO instrument (created|replaced) (\S+)$/,
    params: ['verb', 'code'],
    localise: { verb: 'phrase:PRO_VERB' },
  },
  {
    key: 'AUDIT.DESC.PRO_RESPONSE',
    re: /^PRO response recorded \((\S+), (\S+)\)$/,
    params: ['instrument', 'source'],
    localise: { source: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PRO_ALERT_ACK',
    re: /^PRO safety alert acknowledged$/,
  },
  {
    key: 'AUDIT.DESC.LAB_DUPLICATE_ACKNOWLEDGED',
    re: /^Duplicate warning acknowledged \(order anyway\): lab order (\S+) placed while active order (\S+) of the same test is open$/,
    params: ['order', 'earlier'],
  },
  {
    key: 'AUDIT.DESC.LAB_DUPLICATE_UNACKNOWLEDGED',
    re: /^Duplicate warning not acknowledged: lab order (\S+) placed while active order (\S+) of the same test is open$/,
    params: ['order', 'earlier'],
  },
  {
    key: 'AUDIT.DESC.IMAGING_DUPLICATE_OVERRIDDEN',
    re: /^Duplicate warning overridden \((\S+)\): imaging order (\S+) placed while recent order (\S+) of the same study exists$/,
    params: ['reason', 'order', 'earlier'],
    localise: { reason: 'enum' },
  },
  {
    key: 'AUDIT.DESC.IMAGING_DUPLICATE_NOT_OVERRIDDEN',
    re: /^Duplicate warning not overridden: imaging order (\S+) placed while recent order (\S+) of the same study exists$/,
    params: ['order', 'earlier'],
  },
  // V316: key images on an imaging study (ids only, never a file name or caption).
  {
    key: 'AUDIT.DESC.IMAGING_IMAGE_ATTACHED_TO_REPORT',
    re: /^Imaging image (\S+) attached to imaging order (\S+) \(report (\S+)\)$/,
    params: ['image', 'order', 'report'],
  },
  {
    key: 'AUDIT.DESC.IMAGING_IMAGE_ATTACHED',
    re: /^Imaging image (\S+) attached to imaging order (\S+)$/,
    params: ['image', 'order'],
  },
  {
    key: 'AUDIT.DESC.IMAGING_IMAGE_VIEWED',
    re: /^Imaging image (\S+) of imaging order (\S+) viewed$/,
    params: ['image', 'order'],
  },
  {
    key: 'AUDIT.DESC.IMAGING_IMAGE_REMOVED',
    re: /^Imaging image (\S+) removed from imaging order (\S+)$/,
    params: ['image', 'order'],
  },
  // QA D2464 / D2466 (r64): a key image withdrawn after signing; a caption corrected (ids only).
  {
    key: 'AUDIT.DESC.IMAGING_IMAGE_WITHDRAWN',
    re: /^Imaging image (\S+) withdrawn from imaging order (\S+)$/,
    params: ['image', 'order'],
  },
  {
    key: 'AUDIT.DESC.IMAGING_IMAGE_CAPTION_CORRECTED',
    re: /^Imaging image (\S+) caption corrected on imaging order (\S+)$/,
    params: ['image', 'order'],
  },
  // QA D2342 (r63): the patient's « Imagerie » page (ids only).
  {
    key: 'AUDIT.DESC.IMAGING_PORTAL_READ_BY_PROXY',
    re: /^Imaging reports of patient (\S+) read on the patient portal by a proxy \((\S+) studies\)$/,
    params: ['patient', 'count'],
  },
  {
    key: 'AUDIT.DESC.IMAGING_PORTAL_READ',
    re: /^Imaging reports of patient (\S+) read on the patient portal \((\S+) studies\)$/,
    params: ['patient', 'count'],
  },
  {
    key: 'AUDIT.DESC.IMAGING_PORTAL_IMAGE_VIEWED_BY_PROXY',
    re: /^Imaging image (\S+) of imaging order (\S+) viewed on the patient portal by a proxy$/,
    params: ['image', 'order'],
  },
  {
    key: 'AUDIT.DESC.IMAGING_PORTAL_IMAGE_VIEWED',
    re: /^Imaging image (\S+) of imaging order (\S+) viewed on the patient portal$/,
    params: ['image', 'order'],
  },
  {
    key: 'AUDIT.DESC.LAB_CORRECTION',
    re: /^Correction: result (\S+) supersedes released result (\S+)$/,
    params: ['correction', 'original'],
  },
  {
    key: 'AUDIT.DESC.LAB_AUTOVERIFICATION',
    re: /^Autoverification on analyzer flag N via (.+)$/,
    params: ['source'],
  },
  {
    key: 'AUDIT.DESC.HL7_ORU_INGESTED',
    re: /^ORU\^R01 ingested via (.+)$/,
    params: ['source'],
  },
  {
    key: 'AUDIT.DESC.HL7_ADT',
    re: /^ADT\^(A0[1-4]) (auto-create|transfer|discharge) — (.*)$/,
    params: ['type', 'what', 'details'],
    localise: { what: 'phrase:ADT_EVENT' },
  },
  {
    key: 'AUDIT.DESC.DICOM_LOOKUP_AUDIT_ONLY',
    re: /^DICOM proxy QIDO-RS lookup for study (\S+) — audit-only \(upstream client not configured\)$/,
    params: ['study'],
  },
  {
    key: 'AUDIT.DESC.DICOM_LOOKUP',
    re: /^DICOM proxy QIDO-RS lookup for study (\S+) — upstream returned (\S+) instances$/,
    params: ['study', 'count'],
  },
  {
    key: 'AUDIT.DESC.DICOM_FETCH_MISSING',
    re: /^DICOM proxy WADO-RS fetch for study (\S+) instance (\S+) — not available$/,
    params: ['study', 'instance'],
  },
  {
    key: 'AUDIT.DESC.DICOM_FETCH',
    re: /^DICOM proxy WADO-RS fetch for study (\S+) instance (\S+) — (\S+) bytes$/,
    params: ['study', 'instance', 'bytes'],
  },
  {
    key: 'AUDIT.DESC.FHIR_EXPORT_QUEUED',
    re: /^FHIR \$export job queued \((.*)\)$/,
    params: ['scope'],
  },
  {
    key: 'AUDIT.DESC.FHIR_EXPORT_COMPLETED',
    re: /^FHIR \$export job completed \((\S+) patient\(s\)\)$/,
    params: ['count'],
  },
  {
    key: 'AUDIT.DESC.FHIR_EXPORT_FAILED',
    re: /^FHIR \$export job failed: (.*)$/,
    params: ['error'],
  },
  {
    key: 'AUDIT.DESC.FHIR_EXPORT_CANCELLED',
    re: /^FHIR \$export job cancelled$/,
  },
  {
    key: 'AUDIT.DESC.FHIR_PUT_LAB_NOTE',
    re: /^FHIR PUT appended note to LabResult\/(\S+)$/,
    params: ['id'],
  },
  {
    key: 'AUDIT.DESC.FHIR_PUT_ENCOUNTER',
    re: /^FHIR PUT applied chief-complaint \/ checkout updates to Encounter\/(\S+)$/,
    params: ['id'],
  },
  {
    key: 'AUDIT.DESC.FHIR_PUT_PATIENT',
    re: /^FHIR PUT applied contact\/address updates to Patient\/(\S+)$/,
    params: ['id'],
  },
  {
    key: 'AUDIT.DESC.FHIR_CONDITIONAL_MATCH',
    re: /^FHIR conditional-create matched an active MRN — returned existing Patient\/(\S+)$/,
    params: ['id'],
  },
  {
    key: 'AUDIT.DESC.CDS_OVERRIDE_CREATED',
    re: /^Critical decision-support alert overridden at creation$/,
  },
  {
    key: 'AUDIT.DESC.CDS_OVERRIDE_EDITED',
    re: /^Critical decision-support alert overridden on edit$/,
  },
  {
    key: 'AUDIT.DESC.DOSE_LIMITS_APPROVED',
    re: /^Paediatric dose limits approved on catalog item '(.*)'$/,
    params: ['item'],
  },
  {
    key: 'AUDIT.DESC.DOSE_LIMITS_DEFAULT',
    re: /^Paediatric dose limits set as a default pending approval on catalog item '(.*)'$/,
    params: ['item'],
  },
  {
    key: 'AUDIT.DESC.RX_SIGN_REAUTH_VERIFIED',
    re: /^Re-authentication to sign a controlled-drug prescription verified by (\S+)$/,
    params: ['method'],
    localise: { method: 'phrase:RX_SIGN_REAUTH' },
  },
  {
    key: 'AUDIT.DESC.RX_SIGN_REAUTH_REFUSED',
    re: /^Re-authentication to sign a controlled-drug prescription refused \((\S+)\)$/,
    params: ['reason'],
    localise: { reason: 'phrase:RX_SIGN_REAUTH' },
  },
  {
    // QA D1563 / D1555: a nurse or a pharmacist acknowledged a documented allergy, with a reason
    // (rows written before Policy 4, which replaced that release by the prescriber's review).
    key: 'AUDIT.DESC.ALLERGY_ACKNOWLEDGED',
    re: /^Allergy acknowledged with a reason$/,
  },
  {
    // Policy 4: a gate passed an allergy conflict under the prescriber's CONTINUE review.
    key: 'AUDIT.DESC.ALLERGY_PASSED_UNDER_REVIEW',
    re: /^Allergy conflict passed under the prescriber's review$/,
  },
  {
    // Policy 4: a prescriber reviewed an allergy conflict on a signed order.
    key: 'AUDIT.DESC.ALLERGY_REVIEWED_BY_PRESCRIBER',
    re: /^Allergy conflict reviewed by the prescriber$/,
  },
  {
    // Policy 4: the signer's acknowledgement at the signature is the review of that conflict.
    key: 'AUDIT.DESC.ALLERGY_REVIEWED_AT_SIGNATURE',
    re: /^Allergy conflict reviewed at signature$/,
  },
  {
    key: 'AUDIT.DESC.ALLERGY_OVERRIDE',
    re: /^Allergy override (recorded at creation|recorded on edit|recorded at signature|reason replaced on edit|cleared: the edited order no longer conflicts)$/,
    params: ['what'],
    localise: { what: 'phrase:ALLERGY_OVERRIDE' },
  },
  {
    key: 'AUDIT.DESC.RX_BACK_ORDER_DATE',
    re: /^Prescription (\S+) placed on back order, estimated restock: (\S+)$/,
    params: ['rx', 'date'],
  },
  {
    key: 'AUDIT.DESC.RX_BACK_ORDER',
    re: /^Prescription (\S+) placed on back order$/,
    params: ['rx'],
  },
  {
    key: 'AUDIT.DESC.RX_PRINTED',
    re: /^Prescription (\S+) printed for patient$/,
    params: ['rx'],
  },
  {
    key: 'AUDIT.DESC.DEATH_RECORD_ENTERED_IN_ERROR',
    re: /^Death record (\S+) marked entered in error; the patient is no longer recorded as deceased\. Stays, visits and appointments the death closed were not reopened\. Reason: ([\s\S]*)$/,
    params: ['record', 'reason'],
  },
  // QA D1516: the entered-in-error description now counts what the death had closed.
  {
    key: 'AUDIT.DESC.DEATH_RECORD_ENTERED_IN_ERROR_REVIEW_FILED',
    re: /^Death record (\S+) marked entered in error; the patient is no longer recorded as deceased\. Stays, visits and appointments the death closed were not reopened: (\d+) stay\(s\) marked closed in error, (\d+) visit\(s\), (\d+) appointment\(s\); an urgent review was filed\. Reason: ([\s\S]*)$/,
    params: ['record', 'stays', 'visits', 'appointments', 'reason'],
  },
  {
    key: 'AUDIT.DESC.DEATH_RECORD_ENTERED_IN_ERROR_NOTHING_CLOSED',
    re: /^Death record (\S+) marked entered in error; the patient is no longer recorded as deceased\. Stays, visits and appointments the death closed were not reopened: (\d+) stay\(s\) marked closed in error, (\d+) visit\(s\), (\d+) appointment\(s\); nothing to review\. Reason: ([\s\S]*)$/,
    params: ['record', 'stays', 'visits', 'appointments', 'reason'],
  },
  {
    key: 'AUDIT.DESC.DEATH_DISPUTED',
    re: /^Death status disputed at hospital (\S+) by doctor (\S+), death record (\S+) version (\S+): assessed alive and identity verified\. Care permitted pending review\. Reason: ([\s\S]*)$/,
    params: ['hospital', 'doctor', 'record', 'version', 'reason'],
  },
  {
    key: 'AUDIT.DESC.DEATH_UPHELD',
    re: /^Death upheld: dispute (\S+) closed by (\S+), the block on care is back at hospital (\S+)\. Reason: ([\s\S]*)$/,
    params: ['dispute', 'actor', 'hospital', 'reason'],
  },
  {
    key: 'AUDIT.DESC.ORDER_REVIEW_HELD_AGAIN',
    re: /^Order held again after a change: (\S+) (\S+) changed after its reconfirmation, dispute (\S+)$/,
    params: ['type', 'order', 'dispute'],
    localise: { type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ORDER_REVIEW_DOSE_BEFORE_HOLD',
    re: /^Dose charted as given before the hold: (\S+) (\S+) at (\S+), dispute (\S+), by (\S+)$/,
    params: ['type', 'order', 'at', 'dispute', 'actor'],
    localise: { type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ORDER_REVIEW_CONTINUED',
    re: /^Order reconfirmed unchanged: (\S+) (\S+) version (\S+), dispute (\S+), by (\S+)$/,
    params: ['type', 'order', 'version', 'dispute', 'actor'],
    localise: { type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ORDER_REVIEW_REPLACED',
    re: /^Order replaced by (\S+): (\S+) (\S+) version (\S+), dispute (\S+), by (\S+)$/,
    params: ['replacement', 'type', 'order', 'version', 'dispute', 'actor'],
    localise: { type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ORDER_REVIEW_STOPPED',
    re: /^Order stopped: (\S+) (\S+) version (\S+), dispute (\S+), by (\S+)$/,
    params: ['type', 'order', 'version', 'dispute', 'actor'],
    localise: { type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.ORDER_REVIEW_ASSESSED',
    re: /^Running treatment assessed: (\S+) (\S+) version (\S+), dispute (\S+), by (\S+)$/,
    params: ['type', 'order', 'version', 'dispute', 'actor'],
    localise: { type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.RX_SIGNED_WITHDRAWN_BY_PRESCRIBER',
    re: /^Signed prescription withdrawn by its prescriber$/,
  },
  {
    key: 'AUDIT.DESC.RX_SIGNED_WITHDRAWN_BY_COLLEAGUE',
    re: /^Signed prescription withdrawn by another prescriber of the hospital$/,
  },
  {
    key: 'AUDIT.DESC.RX_ROUTED_PARTNER',
    re: /^Prescription (\S+) routed to partner pharmacy (.+)$/,
    params: ['rx', 'pharmacy'],
  },
  {
    key: 'AUDIT.DESC.RX_CLARIFICATION_REQUESTED',
    re: /^Clarification requested on prescription (\S+)$/,
    params: ['rx'],
  },
  {
    key: 'AUDIT.DESC.RX_CLARIFICATION_RESOLVED',
    re: /^Clarification resolved on prescription (\S+); returned to (\S+)$/,
    params: ['rx', 'status'],
    localise: { status: 'enum' },
  },
  {
    key: 'AUDIT.DESC.DISPENSED_UNITS',
    re: /^Dispensed (\S+) units of (.+) to patient (\S+)$/,
    params: ['quantity', 'drug', 'patient'],
  },
  {
    key: 'AUDIT.DESC.DISPENSED',
    re: /^Dispensed (\S+) (\S+) of (.+) to patient (\S+)$/,
    params: ['quantity', 'unit', 'drug', 'patient'],
  },
  {
    key: 'AUDIT.DESC.DISPENSE_CANCELLED',
    re: /^Cancelled dispense of (\S+) (.*)$/,
    params: ['quantity', 'what'],
  },
  {
    key: 'AUDIT.DESC.DISPENSE_SUBSTITUTED_NO_REASON',
    re: /^Substituted dispense for (.+) — reason: \(no reason provided\)$/,
    params: ['drug'],
  },
  {
    key: 'AUDIT.DESC.DISPENSE_SUBSTITUTED',
    re: /^Substituted dispense for (.+) — reason: (.*)$/,
    params: ['drug', 'reason'],
  },
  {
    key: 'AUDIT.DESC.FILL_PREPARED_UNITS',
    re: /^Prepared (\S+) units for collection, prescription (\S+)$/,
    params: ['quantity', 'rx'],
  },
  {
    key: 'AUDIT.DESC.FILL_PREPARED',
    re: /^Prepared (\S+) (\S+) for collection, prescription (\S+)$/,
    params: ['quantity', 'unit', 'rx'],
  },
  {
    key: 'AUDIT.DESC.FILL_CANCELLED',
    re: /^Prepared fill cancelled, prescription (\S+), reason (.*)$/,
    params: ['rx', 'reason'],
    localise: { reason: 'enum' },
  },
  {
    key: 'AUDIT.DESC.FILL_HANDED_OVER',
    re: /^Prepared fill handed over, prescription (\S+)$/,
    params: ['rx'],
  },
  {
    key: 'AUDIT.DESC.FILL_SUBSTITUTION',
    re: /^Substitution recorded on a prepared fill, prescription (\S+)$/,
    params: ['rx'],
  },
  {
    key: 'AUDIT.DESC.STOCK_RECEIVED',
    re: /^Stock received: (\S+) units, lot (.+)$/,
    params: ['quantity', 'lot'],
  },
  {
    key: 'AUDIT.DESC.STOCK_TRANSACTION_REASON',
    re: /^([A-Z_]+): (\S+) units — (.*)$/,
    params: ['type', 'quantity', 'reason'],
    localise: { type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.STOCK_TRANSACTION',
    re: /^([A-Z_]+): (\S+) units$/,
    params: ['type', 'quantity'],
    localise: { type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.STOCK_REORDER_ALERT',
    re: /^Low stock alert: (.+?) at (.+) — (\S+) on hand, reorder threshold (\S+)$/,
    params: ['medication', 'pharmacy', 'onHand', 'threshold'],
  },
  // QA D905 sibling: before #968 the low-stock row was written in the
  // language of whoever triggered the sweep.
  {
    key: 'AUDIT.DESC.STOCK_REORDER_ALERT_FR',
    re: /^Alerte de stock bas : (.+?) à (.+) — (\S+) en stock, seuil de réapprovisionnement (\S+)$/,
    params: ['medication', 'pharmacy', 'onHand', 'threshold'],
  },
  {
    key: 'AUDIT.DESC.STOCK_REORDER_ALERT_ES',
    re: /^Alerta de stock bajo: (.+?) en (.+) — (\S+) disponibles, umbral de reposición (\S+)$/,
    params: ['medication', 'pharmacy', 'onHand', 'threshold'],
  },
  {
    key: 'AUDIT.DESC.CATALOG_ITEM_DEACTIVATED',
    re: /^Deactivated medication catalog item '(.*)'$/,
    params: ['name'],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_DEACTIVATED',
    re: /^Deactivated pharmacy '(.*)'$/,
    params: ['name'],
  },
  {
    key: 'AUDIT.DESC.MTM_REVIEW_STARTED_ALERT',
    re: /^MTM review started for patient (\S+) \(polypharmacy alert\)$/,
    params: ['patient'],
  },
  {
    key: 'AUDIT.DESC.MTM_REVIEW_STARTED',
    re: /^MTM review started for patient (\S+)$/,
    params: ['patient'],
  },
  {
    key: 'AUDIT.DESC.MTM_INTERVENTION_ON_CREATION',
    re: /^MTM intervention recorded on creation for patient (\S+)$/,
    params: ['patient'],
  },
  {
    key: 'AUDIT.DESC.MTM_INTERVENTION',
    re: /^MTM intervention recorded for review (\S+)$/,
    params: ['review'],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_PAYMENT_VOIDED',
    re: /^Pharmacy payment voided, refund due: (\S+) (\S+) for cancelled dispense (\S+)$/,
    params: ['amount', 'currency', 'dispense'],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_PAYMENT',
    re: /^Pharmacy payment (\S+) (\S+) (\S+) for dispense (\S+)$/,
    params: ['method', 'amount', 'currency', 'dispense'],
    localise: { method: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PHARMACY_CLAIM_CREATED',
    re: /^Pharmacy claim created \((\S+)\) amount (\S+) (\S+) for dispense (\S+)$/,
    params: ['type', 'amount', 'currency', 'dispense'],
    localise: { type: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PHARMACY_CLAIM_SUBMITTED',
    re: /^Pharmacy claim submitted to payer; amount (\S+) (\S+)$/,
    params: ['amount', 'currency'],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_CLAIM_STATUS',
    re: /^Pharmacy claim (accepted|rejected|paid) \(status=(\S+)\)$/,
    params: ['verb', 'status'],
    localise: { verb: 'phrase:CLAIM_VERB', status: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PARTNER_DECISION',
    re: /^Partner pharmacy (accepted|rejected|confirmed dispense for) prescription (\S+)$/,
    params: ['verb', 'rx'],
    localise: { verb: 'phrase:PARTNER_VERB' },
  },
  {
    key: 'AUDIT.DESC.PARTNER_SMS_DECISION',
    re: /^Partner SMS (?:reply )?(accepted|rejected|confirmed dispense for) prescription (\S+)$/,
    params: ['verb', 'rx'],
    localise: { verb: 'phrase:PARTNER_VERB' },
  },
  {
    key: 'AUDIT.DESC.PARTNER_TIMEOUT_REJECTED',
    re: /^Partner auto-rejected after timeout for prescription (\S+)$/,
    params: ['rx'],
  },
  {
    key: 'AUDIT.DESC.PARTNER_NO_SHOW',
    re: /^Partner no-show recorded for prescription (\S+); returned to the hospital queue$/,
    params: ['rx'],
  },
  {
    key: 'AUDIT.DESC.PARTNER_SMS_UNREADABLE',
    re: /^Partner SMS reply from (\S+) could not be read as an accept\/refuse\/dispense reply and was not applied; a person must action it\. Prescription: (.*)$/,
    params: ['sender', 'rx'],
  },
  {
    key: 'AUDIT.DESC.PARTNER_SMS_UNKNOWN_SENDER',
    re: /^Partner SMS reply for reference (\S+) from unrecognised sender (\S+) was ignored; offer still open for prescription\(s\) (.*)$/,
    params: ['reference', 'sender', 'rx'],
  },
  {
    key: 'AUDIT.DESC.PARTNER_ON_CLOSED_RX',
    re: /^(.+) on prescription (\S+), which is (\S+): the prescription was not changed; offer closed$/,
    params: ['what', 'rx', 'status'],
    localise: { what: 'phrase:PARTNER_EVENT', status: 'enum' },
  },
  {
    key: 'AUDIT.DESC.PARTNER_DISPENSE_OF_HELD_RX',
    re: /^(.+) confirmed dispensing prescription (\S+), which is held for review; the hold stands\. A person must check with the patient and the partner\.$/,
    params: ['what', 'rx'],
    localise: { what: 'phrase:PARTNER_EVENT' },
  },
  {
    key: 'AUDIT.DESC.PARTNER_OFFER_RECALLED',
    re: /^Partner offer recalled: prescription (\S+) is held for review$/,
    params: ['rx'],
  },
  {
    key: 'AUDIT.DESC.PARTNER_DISPENSE_ON_CLOSED_RX',
    re: /^(.+) confirmed dispensing prescription (\S+), which is (\S+); the prescription was not changed\. A person must check with the patient and the partner\.$/,
    params: ['what', 'rx', 'status'],
    localise: { what: 'phrase:PARTNER_EVENT', status: 'enum' },
  },
  {
    key: 'AUDIT.DESC.SMS_DISPATCH_FAILED',
    re: /^SMS dispatch of prescription (\S+) to pharmacy (\S+) failed \((.*)\); transmission (\S+) recorded FAILED; prescription now (\S+)$/,
    params: ['rx', 'pharmacy', 'error', 'transmission', 'status'],
    localise: { status: 'enum' },
  },
  {
    key: 'AUDIT.DESC.WARD_ORDER_DISCONTINUED_COUNTER',
    re: /^Ward order of an ended stay discontinued \(QA D792\): prepared fill cancelled at the counter \((.*)\)$/,
    params: ['reason'],
    localise: { reason: 'enum' },
  },
  {
    key: 'AUDIT.DESC.WARD_ORDER_DISCONTINUED',
    re: /^Ward order of an ended stay discontinued \(QA D792\): (.+)$/,
    params: ['trigger'],
    localise: { trigger: 'phrase:WARD_ORDER_TRIGGER' },
  },
  {
    key: 'AUDIT.DESC.QUEUE_CLAIM_TAKEN',
    re: /^Work-queue claim taken, prescription (\S+)$/,
    params: ['rx'],
  },
  {
    key: 'AUDIT.DESC.QUEUE_CLAIM_TAKEN_OVER_BY',
    re: /^Work-queue claim taken over from user (\S+) \(claimed (\S+)\) by (\S+), prescription (\S+)$/,
    params: ['user', 'claimed', 'by', 'rx'],
  },
  {
    key: 'AUDIT.DESC.QUEUE_CLAIM_TAKEN_OVER',
    re: /^Work-queue claim taken over from user (\S+) \(claimed (\S+)\), prescription (\S+)$/,
    params: ['user', 'claimed', 'rx'],
  },
  {
    key: 'AUDIT.DESC.QUEUE_CLAIM_RELEASED_BY_OTHER',
    re: /^Work-queue claim of user (\S+) \(claimed (\S+)\) released \((\S+)\), prescription (\S+)$/,
    params: ['user', 'claimed', 'why', 'rx'],
    localise: { why: 'enum' },
  },
  {
    key: 'AUDIT.DESC.QUEUE_CLAIM_EXPIRED',
    re: /^Work-queue claim of user (\S+) \(claimed (\S+)\) expired at (\S+), found (\S+), prescription (\S+)$/,
    params: ['user', 'claimed', 'expired', 'found', 'rx'],
  },
  {
    key: 'AUDIT.DESC.QUEUE_CLAIM_RELEASED',
    re: /^Work-queue claim released \((\S+)\), claimed (\S+), prescription (\S+)$/,
    params: ['why', 'claimed', 'rx'],
    localise: { why: 'enum' },
  },
  {
    // QA D1100: an inpatient's bed freed, with its reason in the details.
    key: 'AUDIT.DESC.ADMISSION_BED_RELEASED',
    re: /^Bed (.+) released from admission (\S+)$/,
    params: ['bed', 'admission'],
  },
  {
    // QA D1096: the paediatric dose review an order needed before signing.
    key: 'AUDIT.DESC.PRESCRIPTION_PEDIATRIC_DOSE_REVIEWED',
    re: /^Paediatric dose review recorded$/,
  },
  {
    key: 'AUDIT.DESC.PAYMENT_EVENT',
    re: /^(Payment recorded|Payment reversed|Pending payment confirmed|Pending payment rejected|Check-in copay recorded): payment (\S+) on invoice (\S+)$/,
    params: ['what', 'payment', 'invoice'],
    localise: { what: 'phrase:PAYMENT_EVENT' },
  },
  {
    // Billing-money plan PR 8 (QA D1900): each step of a refund (ids only; the amount and reason stay on the row).
    key: 'AUDIT.DESC.REFUND_EVENT',
    re: /^(Refund requested|Refund approved|Refund rejected|Refund paid out|Refund cancelled): refund (\S+) on invoice (\S+)$/,
    params: ['what', 'refund', 'invoice'],
    localise: { what: 'phrase:REFUND_EVENT' },
  },
  {
    // Billing-money plan PR 7: a payer's share of an invoice applied, replaced, cancelled or decided (ids only).
    key: 'AUDIT.DESC.PAYER_SHARE_EVENT',
    re: /^(Payer share applied|Payer share applied after a credit note|Payer share replaced after a credit note|Payer share cancelled with its invoice|Payer share claimed|Payer share paid|Payer share rejected): share (\S+) on invoice (\S+)$/,
    params: ['what', 'share', 'invoice'],
    localise: { what: 'phrase:PAYER_SHARE_EVENT' },
  },
  {
    // Billing-money plan PR 2: the price list (ids only; the label, price and reason stay on the rows).
    key: 'AUDIT.DESC.PRICE_LIST_ITEM_CREATED',
    re: /^Price list item (\S+) created with price version (\S+)$/,
    params: ['item', 'version'],
  },
  {
    key: 'AUDIT.DESC.PRICE_LIST_ITEM_UPDATED',
    re: /^Price list item (\S+) updated$/,
    params: ['item'],
  },
  {
    key: 'AUDIT.DESC.PRICE_LIST_ITEM_DEACTIVATED',
    re: /^Price list item (\S+) deactivated$/,
    params: ['item'],
  },
  {
    key: 'AUDIT.DESC.PRICE_VERSION_ADDED',
    re: /^Price version (\S+) added to price list item (\S+), effective (\S+)$/,
    params: ['version', 'item', 'from'],
  },
  {
    // Billing-money plan PR 6: the payer catalogue (ids only; names, rates and reasons stay on the rows).
    key: 'AUDIT.DESC.PAYER_CREATED',
    re: /^Payer (\S+) created$/,
    params: ['payer'],
  },
  {
    key: 'AUDIT.DESC.PAYER_UPDATED',
    re: /^Payer (\S+) updated$/,
    params: ['payer'],
  },
  {
    key: 'AUDIT.DESC.PAYER_DEACTIVATED',
    re: /^Payer (\S+) deactivated$/,
    params: ['payer'],
  },
  {
    key: 'AUDIT.DESC.PAYER_PLAN_CREATED',
    re: /^Payer plan (\S+) of payer (\S+) created$/,
    params: ['plan', 'payer'],
  },
  {
    key: 'AUDIT.DESC.PAYER_PLAN_UPDATED',
    re: /^Payer plan (\S+) of payer (\S+) updated$/,
    params: ['plan', 'payer'],
  },
  {
    key: 'AUDIT.DESC.PAYER_RULE_ADDED',
    re: /^Rule (\S+) added to payer plan (\S+), effective (\S+), closed \[([^\]]*)\], replaced \[([^\]]*)\]$/,
    params: ['rule', 'plan', 'from', 'closed', 'replaced'],
  },
  {
    // Review of #1202: plan terms are dated versions (ids only).
    key: 'AUDIT.DESC.PAYER_TERMS_ADDED',
    re: /^Terms (\S+) added to payer plan (\S+), effective (\S+), closed \[([^\]]*)\], replaced \[([^\]]*)\]$/,
    params: ['terms', 'plan', 'from', 'closed', 'replaced'],
  },
  {
    key: 'AUDIT.DESC.PAYER_RULE_ENDED',
    re: /^Rule (\S+) of payer plan (\S+) ended$/,
    params: ['rule', 'plan'],
  },
  {
    key: 'AUDIT.DESC.COVERAGE_PLAN_LINKED',
    re: /^Patient insurance (\S+) linked to payer plan (\S+)$/,
    params: ['insurance', 'plan'],
  },
  {
    key: 'AUDIT.DESC.COVERAGE_PLAN_UNLINKED',
    re: /^Patient insurance (\S+) unlinked from its payer plan$/,
    params: ['insurance'],
  },
  {
    // Billing-money plan PR 4: a price-list CSV file applied (the import id and the counts only).
    key: 'AUDIT.DESC.PRICE_LIST_IMPORTED',
    re: /^Price list import (\S+) applied: (\d+) new items, (\d+) new prices, (\d+) unchanged$/,
    params: ['import', 'items', 'prices', 'unchanged'],
  },
  {
    // Billing-money plan PR 3 (D-2): a price-list price overridden on a draft line (ids only; the prices and the
    // reason stay on the line).
    key: 'AUDIT.DESC.INVOICE_LINE_PRICE_OVERRIDDEN',
    re: /^Tariff price version (\S+) overridden on draft line (\S+) of invoice (\S+)$/,
    params: ['version', 'line', 'invoice'],
  },
  {
    // Review #1197: an overridden line set back to its price-list price, and a draft line deleted (ids only;
    // the reason a price-list line was removed is in the row's details).
    key: 'AUDIT.DESC.INVOICE_LINE_PRICE_RESTORED',
    re: /^Tariff price version (\S+) restored on draft line (\S+) of invoice (\S+)$/,
    params: ['version', 'line', 'invoice'],
  },
  {
    key: 'AUDIT.DESC.INVOICE_LINE_DELETED_TARIFF',
    re: /^Draft line (\S+) of invoice (\S+) deleted \(tariff price version (\S+)\)$/,
    params: ['line', 'invoice', 'version'],
  },
  {
    key: 'AUDIT.DESC.INVOICE_LINE_DELETED',
    re: /^Draft line (\S+) of invoice (\S+) deleted$/,
    params: ['line', 'invoice'],
  },
  {
    // QA D974: a discount, waiver or credit note on an invoice.
    key: 'AUDIT.DESC.INVOICE_ADJUSTMENT',
    re: /^Invoice adjustment ([A-Z_]+) on invoice (\S+)$/,
    params: ['kind', 'invoice'],
    localise: { kind: 'phrase:INVOICE_ADJUSTMENT' },
  },
  {
    // QA D1105: a discount, waiver or credit note undone; the reason is kept on the row, not in the audit text.
    key: 'AUDIT.DESC.INVOICE_ADJUSTMENT_REVERSED',
    re: /^Invoice adjustment ([A-Z_]+) (\S+) reversed with a recorded reason on invoice (\S+)$/,
    params: ['kind', 'adjustment', 'invoice'],
    localise: { kind: 'phrase:INVOICE_ADJUSTMENT' },
  },
  {
    // QA D1069: an issued invoice cancelled; the reason is kept on the invoice, not in the audit text.
    key: 'AUDIT.DESC.INVOICE_CANCELLED',
    re: /^Invoice cancelled with a recorded reason: invoice (\S+)$/,
    params: ['invoice'],
  },
  {
    // Who is on the row; the unit and the request by id only.
    key: 'AUDIT.DESC.EMERGENCY_BLOOD_RELEASED',
    re: /^Emergency uncrossmatched release of blood unit (\S+) against transfusion request (\S+)$/,
    params: ['unit', 'request'],
  },
  {
    key: 'AUDIT.DESC.EMERGENCY_BLOOD_RELEASED_O_POSITIVE_STATED',
    re: /^Emergency release on a documented O positive decision of blood unit (\S+) against transfusion request (\S+), O negative stated not obtainable in time, stock shown (\S+)$/,
    params: ['unit', 'request', 'shown'],
  },
  {
    key: 'AUDIT.DESC.EMERGENCY_BLOOD_RELEASED_INCOMPATIBLE_STATED',
    re: /^Emergency release, serologically incompatible, of blood unit (\S+) against transfusion request (\S+), O negative stated not obtainable in time, stock shown (\S+)$/,
    params: ['unit', 'request', 'shown'],
  },
  {
    key: 'AUDIT.DESC.EMERGENCY_BLOOD_RELEASED_O_POSITIVE',
    re: /^Emergency release on a documented O positive decision of blood unit (\S+) against transfusion request (\S+)$/,
    params: ['unit', 'request'],
  },
  {
    key: 'AUDIT.DESC.EMERGENCY_BLOOD_RELEASED_INCOMPATIBLE',
    re: /^Emergency release, serologically incompatible, of blood unit (\S+) against transfusion request (\S+)$/,
    params: ['unit', 'request'],
  },
  {
    key: 'AUDIT.DESC.NEWBORN_REGISTRATION_PENDING',
    re: /^Newborn registration pending after a recorded delivery$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.NEWBORN_REGISTERED_FROM_DELIVERY',
    re: /^Newborn registered from a recorded delivery$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.APPOINTMENT_OVERBOOKED',
    re: /^Appointment overbooked outside the published slots$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.REGISTRATION_DEACTIVATED',
    re: /^Patient registration deactivated$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.CDS_CRITICAL_OVERRIDDEN_AT_SIGNATURE',
    re: /^Critical decision-support alert overridden at signature$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.SIGNATURE_EXCEPTION_ENDED',
    re: /^Signature exception ended: what is ordered was changed$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.URGENT_DOSE_EXCEPTION_AT_SIGNATURE',
    re: /^Urgent paediatric dose exception recorded at signature$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.DOSE_EXCEPTION_AT_SIGNATURE',
    re: /^Dose exception recorded at signature$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.OBSTETRIC_RULE_SKIPPED_ULTRASOUND',
    re: /^Obstetric rule skipped for an emergency ultrasound order$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.OBSTETRIC_RULE_SKIPPED_REFERRAL',
    re: /^Obstetric rule skipped for an emergency OB\/GYN referral$/,
    params: [],
  },
  {
    // QA D1631: a second labour refused while one is active at another hospital.
    key: 'AUDIT.DESC.LABOR_EPISODE_START_REFUSED',
    re: /^Labour episode start refused: an active labour episode is open at another hospital$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.LABOR_ACTIVE_DISCLOSURE_ON_REFUSAL',
    re: /^Cross-hospital active labour disclosed when refusing a second labour episode$/,
    params: [],
  },
  {
    re: /^Labour episode start refused: care coordination required$/,
    key: 'AUDIT.DESC.LABOR_EPISODE_START_REFUSED_COORDINATION',
    params: [],
  },
  {
    // Labour transfer between hospitals (provisional rule, 2026-11-12).
    key: 'AUDIT.DESC.LABOR_EPISODE_TRANSFERRED_OUT',
    re: /^Labour episode transferred to another hospital with a handover$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.LABOR_EPISODE_TRANSFERRED_IN',
    re: /^Labour episode received from another hospital with a handover$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.LABOR_EPISODE_LEFT_WITHOUT_HANDOVER',
    re: /^Labour episode closed: she arrived at another hospital without a handover$/,
    params: [],
  },
  {
    // QA r46 (provisional): a birth recorded as before her arrival from another hospital.
    key: 'AUDIT.DESC.LABOR_DELIVERY_BORN_BEFORE_ARRIVAL',
    re: /^Birth recorded as before her arrival from another hospital$/,
    params: [],
  },
  {
    // QA D1714: a stillborn baby's fetal-death event, and a corrected baby outcome.
    key: 'AUDIT.DESC.FETAL_DEATH_EVENT_RECORDED',
    re: /^Fetal-death event recorded for a stillborn baby$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.FETAL_DEATH_EVENT_SUPERSEDED',
    re: /^Fetal-death event superseded by a corrected baby outcome$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.DELIVERY_BABY_OUTCOME_CORRECTED',
    re: /^Baby outcome corrected on a delivery record$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.STILLBIRTH_CERTIFICATE_CONFLICT_DEATH',
    re: /^Death certificate declares a stillbirth the delivery now records as born alive$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.STILLBIRTH_CERTIFICATE_CONFLICT_DELIVERY',
    re: /^Delivery records as born alive a baby a death certificate declares stillborn$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.STILLBIRTH_CERTIFICATE_CONFLICT_RECONCILED',
    re: /^Stillbirth certificate conflict reconciled$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.LABOR_EPISODE_HANDOVER_READ',
    re: /^Labour episode handed over by transfer read at the receiving hospital$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.LABOR_EPISODE_ARRIVAL_WITHOUT_HANDOVER',
    re: /^Labour episode opened on arrival from another hospital without a handover$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.OBSTETRIC_RULE_SKIP_CLEARED_ULTRASOUND',
    re: /^Obstetric rule skip cleared on an ultrasound order$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.MFA_RESET_BY_ADMIN',
    re: /^Two-step verification reset by an administrator: (.*)$/,
    params: ['reason'],
  },
  {
    key: 'AUDIT.DESC.MFA_RESET_REFUSED',
    re: /^Two-step verification reset refused: the caller may not administer this account$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.MFA_REMOVED_BY_USER',
    re: /^Two-step verification removed by the user$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.PATIENT_CHANNEL_CHOICES',
    re: /^Patient channel choices recorded at the desk: (.+)$/,
    params: ['changes'],
  },
  {
    // QA r73 D3702: the patient's own change, audited like the desk's.
    key: 'AUDIT.DESC.PATIENT_CHANNEL_CHOICES_SELF',
    re: /^Patient channel choices recorded by the patient: (.+)$/,
    params: ['changes'],
  },
  {
    key: 'AUDIT.DESC.STATUS_WAS',
    re: /^(.+) \(was ([A-Z_]+)\)$/,
    params: ['what', 'previous'],
    localise: { previous: 'enum' },
  },
  // Outbox detail read (the payload carries patient name and identifiers): ids and message type only.
  {
    key: 'AUDIT.DESC.INSTRUMENT_OUTBOX_VIEWED',
    re: /^Instrument outbox message (\S+) \((\S+)\) viewed$/,
    params: ['message', 'type'],
  },
  // QA r65 (D2674-D2676): pharmacy money and stock (constant descriptions, ids on the row).
  {
    key: 'AUDIT.DESC.PHARMACY_FILL_DEFERRED',
    re: /^Fill handed over unpaid, marked to be billed$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_REFUND_REQUESTED',
    re: /^Pharmacy refund requested$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_REFUND_APPROVED',
    re: /^Pharmacy refund approved$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_REFUND_REJECTED',
    re: /^Pharmacy refund rejected$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_REFUND_PAID',
    re: /^Pharmacy refund paid out$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_REFUND_CANCELLED',
    re: /^Pharmacy refund cancelled$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_PACK_SIZE_SET',
    re: /^Pack size set on an inventory item$/,
    params: [],
  },
  // Review of #1239: a selling price set or changed (the figures stay on the price-history row).
  {
    key: 'AUDIT.DESC.PHARMACY_PRICE_CHANGED',
    re: /^Pharmacy selling price changed$/,
    params: [],
  },
  // QA D2702 (V309): a payment reversal asked for, then paid out through the refund approval (ids only).
  {
    key: 'AUDIT.DESC.REVERSAL_EVENT',
    re: /^(Payment reversal requested|Payment reversed): refund (\S+) on invoice (\S+)$/,
    params: ['what', 'refund', 'invoice'],
    localise: { what: 'phrase:REVERSAL_EVENT' },
  },
  // Billing-money plan PR 10 (V309): a till closed, reopened or reviewed (ids only; a reason is in the details).
  {
    key: 'AUDIT.DESC.CASH_SESSION_EVENT',
    re: /^(Cash session closed|Cash session reopened|Cash session reviewed): session (\S+)$/,
    params: ['what', 'session'],
    localise: { what: 'phrase:CASH_SESSION_EVENT' },
  },
  // QA D2703: a draft line with no price-list price priced by hand, or set to 0, with a reason (in the details).
  {
    key: 'AUDIT.DESC.INVOICE_LINE_PRICED_MANUALLY',
    re: /^Draft line (\S+) of invoice (\S+) priced by hand$/,
    params: ['line', 'invoice'],
  },
  // Policy 2026-11-20 (provisional): a minor's sensitive result released to a verified parent (ids and category only).
  {
    key: 'AUDIT.DESC.SENSITIVE_RESULT_RELEASED',
    re: /^Sensitive result \((\S+)\) released to a verified parent or guardian$/,
    params: ['category'],
    localise: { category: 'enum' },
  },
  // Policy 2026-11-20: staff verified that the holder of a family-access grant is the parent or guardian.
  {
    key: 'AUDIT.DESC.PROXY_RELATIONSHIP_VERIFIED',
    re: /^Family-access relationship verified \((\S+)\)$/,
    params: ['relationship'],
    localise: { relationship: 'enum' },
  },
  // Policy 2026-11-20: a recommended sensitive category the lab director confirmed for one test.
  {
    key: 'AUDIT.DESC.SENSITIVITY_RECOMMENDATION_APPLIED',
    re: /^Recommended sensitivity category applied: (\S+) to (\S+)$/,
    params: ['from', 'to'],
    localise: { from: 'enum', to: 'enum' },
  },
  // Policy 2026-11-20: a catalogue medicine's sensitive category (category names only).
  {
    key: 'AUDIT.DESC.MEDICATION_SENSITIVITY_CHANGED',
    re: /^Medication sensitivity category changed from (\S+) to (\S+)$/,
    params: ['from', 'to'],
    localise: { from: 'enum', to: 'enum' },
  },
  // Policy 2026-11-20: the hospital's adolescent confidentiality age (before and after are in the details).
  // r66 D2904: French counts 0 and 1 in the singular (« fixé à 0 an », « fixé à 1 an », it read « 1 ans »),
  // Spanish only 1: each has its own sentence, before the general one.
  {
    key: 'AUDIT.DESC.ADOLESCENT_AGE_SET_ZERO',
    re: /^Adolescent confidentiality age set to 0$/,
  },
  {
    key: 'AUDIT.DESC.ADOLESCENT_AGE_SET_ONE',
    re: /^Adolescent confidentiality age set to 1$/,
  },
  {
    key: 'AUDIT.DESC.ADOLESCENT_AGE_SET',
    re: /^Adolescent confidentiality age set to (\d+)$/,
    params: ['age'],
  },
  {
    key: 'AUDIT.DESC.ADOLESCENT_AGE_RESET',
    re: /^Adolescent confidentiality age reset to the default \((\d+)\)$/,
    params: ['age'],
  },
  // Policy rule 3 (V346, provisional): a hospital's imaging reporting setting changed (the value is in the details).
  {
    key: 'AUDIT.DESC.IMAGING_REPORTING_SETTINGS_CHANGED',
    re: /^Imaging reporting settings changed$/,
  },
  // QA D3450 (r70, V361): an exam of the imaging catalogue changed (its pregnancy flag or body region; the
  // item is the row's resource, the new value is in the details).
  {
    key: 'AUDIT.DESC.IMAGING_CATALOGUE_EXAM_CHANGED',
    re: /^Imaging catalogue exam changed$/,
  },
  // Policy rule 3 (V346): an exam record written (what changed is in the details; never the free text).
  {
    key: 'AUDIT.DESC.IMAGING_EXAM_RECORD_UPDATED',
    re: /^Imaging exam record updated$/,
  },
  // Policy rule 6 (V345): « à facturer » asked for, decided by another person, an urgency override and its
  // review, and an inpatient fill charged to the stay (constant descriptions, ids on the row).
  {
    key: 'AUDIT.DESC.DISPENSE_BILLING_DEFERRAL_REQUESTED',
    re: /^Deferred billing of a fill requested$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.DISPENSE_BILLING_DEFERRAL_APPROVED',
    re: /^Deferred billing of a fill approved$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.DISPENSE_BILLING_DEFERRAL_REJECTED',
    re: /^Deferred billing of a fill rejected$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.DISPENSE_BILLING_DEFERRAL_CANCELLED',
    re: /^Deferred billing of a fill withdrawn$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.DISPENSE_BILLING_URGENCY_OVERRIDE',
    re: /^Fill handed over unpaid on an urgency override$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.DISPENSE_BILLING_OVERRIDE_REVIEWED',
    re: /^Urgency override of a fill's billing reviewed$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.DISPENSE_POSTED_TO_ADMISSION_ACCOUNT',
    re: /^Fill charged to the account of the open stay$/,
    params: [],
  },
  // QA r66 (D2929, D2925): a deferral the hand-over closed because the order left paid, and a money
  // desk confirming a claim the pharmacy had accepted itself (constant descriptions, ids on the row).
  {
    key: 'AUDIT.DESC.DISPENSE_BILLING_DEFERRAL_CLOSED_SETTLED',
    re: /^Deferred billing of a fill closed: the order left the counter with nothing due$/,
    params: [],
  },
  // Review of #1284 (QA D3408): a third-party cover withdrawn because the prepared fill it was approved on was
  // cancelled (constant description, ids on the row).
  {
    key: 'AUDIT.DESC.DISPENSE_BILLING_DEFERRAL_CLOSED_FILL_CANCELLED',
    re: /^Deferred billing of a fill closed: the prepared fill was cancelled$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.PHARMACY_CLAIM_ACCEPTANCE_CONFIRMED',
    re: /^Pharmacy claim acceptance confirmed by a money desk$/,
    params: [],
  },
  // QA r65 D2764: the registrar looked at a pending assignment's activation
  // code to hand it over in person (the code itself is never on the trail).
  {
    key: 'AUDIT.DESC.ACTIVATION_CODE_SHOWN',
    re: /^Activation code shown to the registrar$/,
    params: [],
  },
  // QA D3054 (r68): the prescriber printed the ordonnance (the prescriptions on it, ids only).
  {
    key: 'AUDIT.DESC.ORDONNANCE_PRINTED',
    re: /^Ordonnance printed by its prescriber: prescriptions (\S+)$/,
    params: ['ids'],
  },
  // V356: a hospital's sign-off of a provisional rule set recorded or revoked (key and version only).
  {
    key: 'AUDIT.DESC.CLINICAL_RULE_SIGNED_OFF',
    re: /^Clinical rule set (\S+) signed off \(version (\S+)\)$/,
    params: ['rule', 'version'],
  },
  {
    key: 'AUDIT.DESC.CLINICAL_RULE_SIGNOFF_REVOKED',
    re: /^Clinical rule set (\S+) sign-off revoked \(version (\S+)\)$/,
    params: ['rule', 'version'],
  },
  // V382: the retention purge of the audit tables records itself (counts and cutoff in details).
  {
    key: 'AUDIT.DESC.AUDIT_RETENTION_PURGED',
    re: /^Audit rows past the retention period removed$/,
    params: [],
  },
  // QA r70 D3206: the desk showed or re-sent a patient's portal activation (never the code).
  {
    key: 'AUDIT.DESC.PATIENT_ACTIVATION_CODE_SHOWN',
    re: /^Patient portal activation code shown to the desk$/,
    params: [],
  },
  {
    key: 'AUDIT.DESC.PATIENT_ACTIVATION_RESENT',
    re: /^Patient portal activation re-sent by the desk$/,
    params: [],
  },
];
