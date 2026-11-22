/**
 * QA D1564: a newborn's warnings on a saved vital set. The server judges the set with the
 * newborn assessment's own bands (NewbornAlertRules, provisional pending the neonatal lead's
 * approval) and returns what it found; the portal holds no newborn numbers of its own.
 */
export interface NewbornVitalAlert {
  code: string;
  severity: 'URGENT' | 'WARNING' | 'CAUTION' | 'INFO';
  type: string;
  /** Already in the recorder's language. */
  message: string;
}

const ORDER: Record<NewbornVitalAlert['severity'], number> = {
  URGENT: 0,
  WARNING: 1,
  CAUTION: 1,
  INFO: 2,
};

/** How long the newborn warning stays on screen: it is read after the dialog closes. */
export const NEWBORN_WARNING_MS = 20000;

/**
 * The heading key and the warning lines, most urgent first; null when the set raised nothing.
 * The set is saved either way: these warn, they never refuse.
 */
export function newbornVitalWarning(
  alerts: NewbornVitalAlert[] | null | undefined,
): { headingKey: string; lines: string[] } | null {
  if (!alerts?.length) return null;
  const sorted = [...alerts].sort((a, b) => (ORDER[a.severity] ?? 3) - (ORDER[b.severity] ?? 3));
  const urgent = sorted.some((a) => a.severity === 'URGENT');
  return {
    headingKey: urgent ? 'VITAL_LIMITS.NEWBORN_WARNING_URGENT' : 'VITAL_LIMITS.NEWBORN_WARNING',
    lines: sorted.map((a) => a.message).filter((m) => !!m),
  };
}

/** The two services the announcement needs, typed narrowly so a spec can pass fakes. */
interface WarningToast {
  push(type: 'warning', message: string, durationMs?: number): void;
}
interface Translator {
  instant(key: string, params?: Record<string, unknown>): unknown;
}

/**
 * V356: the hospital's status of a provisional rule set as one sentence (ClinicalRulesService.statusSentence),
 * or null while unknown. When it is known, a warning's heading says what happened and then that status
 * (« … Validée le … par Médecin-chef ») instead of its fixed « Règle provisoire, en attente … ».
 */
export type RuleStatusLookup = (ruleKey: string) => string | null;

/** The heading: the fixed one while the rule's status is unknown, else what happened + the hospital's status. */
function heading(
  translate: Translator,
  fixedKey: string,
  surfaceKey: string,
  ruleKey: string,
  ruleStatus: RuleStatusLookup | undefined,
): string {
  const status = ruleStatus?.(ruleKey);
  return status
    ? `${String(translate.instant(surfaceKey))} ${status}`
    : String(translate.instant(fixedKey));
}

/**
 * QA D2585 (r65): these warnings stay on screen for 20 s, long after the dialog closed and while
 * the nurse records the next patient; a warning that does not name its patient reads as the next
 * one's. Every announcement starts with the patient's name, when the caller knows it.
 */
function withPatient(
  message: string,
  patientName: string | null | undefined,
  translate: Translator,
): string {
  const name = patientName?.trim();
  if (!name) return message;
  return `${String(translate.instant('VITAL_LIMITS.FOR_PATIENT', { patient: name }))} ${message}`;
}

/** Shows the newborn warning after a save, if the set raised one. Returns whether it did. */
export function announceNewbornWarning(
  alerts: NewbornVitalAlert[] | null | undefined,
  toast: WarningToast,
  translate: Translator,
  patientName: string | null | undefined,
  ruleStatus?: RuleStatusLookup,
): boolean {
  const warning = newbornVitalWarning(alerts);
  if (!warning) return false;
  const urgent = warning.headingKey.endsWith('_URGENT');
  const title = heading(
    translate,
    warning.headingKey,
    urgent
      ? 'CLINICAL_RULES.SURFACE.VITALS_NEWBORN_URGENT'
      : 'CLINICAL_RULES.SURFACE.VITALS_NEWBORN',
    'NEWBORN_VITALS',
    ruleStatus,
  );
  toast.push(
    'warning',
    withPatient([title, ...warning.lines].join(' '), patientName, translate),
    NEWBORN_WARNING_MS,
  );
  return true;
}

/**
 * QA D1890 (provisional, pending the obstetric lead's approval): a mother's severe blood pressure
 * after a recorded birth. The server judges the set with the one shared rule (the postpartum
 * observation and the doctor's BPA panel use the same one) and returns the URGENT warning; the
 * portal holds no threshold of its own. The set is saved either way, and nothing waits on an
 * acknowledgement. Returns whether a warning was shown.
 */
export function announcePostpartumWarning(
  alerts: NewbornVitalAlert[] | null | undefined,
  toast: { push(type: 'error', message: string, durationMs?: number): void },
  translate: Translator,
  patientName: string | null | undefined,
  ruleStatus?: RuleStatusLookup,
): boolean {
  const lines = (alerts ?? []).map((a) => a.message).filter((m) => !!m);
  if (!lines.length) return false;
  const title = heading(
    translate,
    'VITAL_LIMITS.POSTPARTUM_WARNING_URGENT',
    'CLINICAL_RULES.SURFACE.VITALS_POSTPARTUM_URGENT',
    'SEVERE_HYPERTENSION_OBSTETRIC',
    ruleStatus,
  );
  toast.push(
    'error',
    withPatient([title, ...lines].join(' '), patientName, translate),
    NEWBORN_WARNING_MS,
  );
  return true;
}

/**
 * r54 (provisional, pending the clinical lead's approval): an adult's severe blood pressure. The
 * server judges the set with the one shared rule (the BPA panel uses the same one) and returns a
 * WARNING asking for a recheck at rest, URGENT when a sign of organ damage was ticked; the portal
 * holds no threshold of its own. The set is saved either way, and nothing waits on an
 * acknowledgement. Returns whether a warning was shown.
 */
export function announceHypertensionWarning(
  alerts: NewbornVitalAlert[] | null | undefined,
  toast: { push(type: 'warning' | 'error', message: string, durationMs?: number): void },
  translate: Translator,
  patientName: string | null | undefined,
  ruleStatus?: RuleStatusLookup,
): boolean {
  const lines = (alerts ?? []).map((a) => a.message).filter((m) => !!m);
  if (!lines.length) return false;
  const urgent = (alerts ?? []).some((a) => a.severity === 'URGENT');
  const title = heading(
    translate,
    urgent ? 'VITAL_LIMITS.ADULT_HTN_URGENT' : 'VITAL_LIMITS.ADULT_HTN_WARNING',
    urgent
      ? 'CLINICAL_RULES.SURFACE.VITALS_ADULT_HTN_URGENT'
      : 'CLINICAL_RULES.SURFACE.VITALS_ADULT_HTN',
    'SEVERE_HYPERTENSION_ADULT',
    ruleStatus,
  );
  toast.push(
    urgent ? 'error' : 'warning',
    withPatient([title, ...lines].join(' '), patientName, translate),
    NEWBORN_WARNING_MS,
  );
  return true;
}

/** A maternal level whose page was SENT: the plain code, with no `-withheld` or `-unsent` ending. */
const MEWS_PAGED_CODE = /^mews-(low-medium|medium|high)$/;

/**
 * Policy 2026-11-20 (provisional, pending a named obstetric clinician's sign-off): the maternal early
 * warning of a pregnant or postpartum woman's set. The server scores the set with its one rule set
 * (national MEWS, CRADLE shock index) and returns the level of concern when a review is due and each
 * shock-index alert; the portal holds no band or threshold of its own. The set is saved either way,
 * and nothing waits on an acknowledgement. Only a plain level code (`mews-low-medium`, `mews-medium`,
 * `mews-high`) means the team was paged: the server ends the code in `-withheld` when its cool-down
 * held the page back (the team was told a moment ago) and in `-unsent` when it reached nobody, and its
 * sentence says so. The heading then never claims the team was told. Returns whether a warning was shown.
 */
export function announceMaternalWarning(
  alerts: NewbornVitalAlert[] | null | undefined,
  toast: { push(type: 'warning' | 'error', message: string, durationMs?: number): void },
  translate: Translator,
  patientName: string | null | undefined,
  ruleStatus?: RuleStatusLookup,
): boolean {
  const lines = (alerts ?? []).map((a) => a.message).filter((m) => !!m);
  if (!lines.length) return false;
  const urgent = (alerts ?? []).some((a) => a.severity === 'URGENT');
  const paged = (alerts ?? []).some((a) => MEWS_PAGED_CODE.test(a.code ?? ''));
  const title = heading(
    translate,
    paged ? 'VITAL_LIMITS.MATERNAL_EWS_PAGED' : 'VITAL_LIMITS.MATERNAL_EWS_WARNING',
    paged
      ? 'CLINICAL_RULES.SURFACE.VITALS_MATERNAL_EWS_PAGED'
      : 'CLINICAL_RULES.SURFACE.VITALS_MATERNAL_EWS',
    'MATERNAL_EARLY_WARNING',
    ruleStatus,
  );
  toast.push(
    urgent ? 'error' : 'warning',
    withPatient([title, ...lines].join(' '), patientName, translate),
    NEWBORN_WARNING_MS,
  );
  return true;
}
