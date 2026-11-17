import { serverInstant } from './date-utils';
import { currentLocale } from './i18n/app-locale';

/** The fields of a visit an encounter picker reads. */
export interface EncounterOptionSource {
  readonly encounterDate?: string | null;
  readonly departmentName?: string | null;
}

/**
 * QA D1309 (round 28): the label of one visit in an encounter picker (lab
 * order, prescription, discharge summary). "02/11/2026 · Médecine générale ·
 * Ambulatoire" was printed four times for a patient with four visits that
 * day, so an order could be attached to the wrong one. Each option now carries
 * the visit's time (HH:mm), after its day and before its department; the
 * template adds the type (and, where the picker shows it, the status) through
 * the `enumLabel` pipe.
 *
 * The time is read like every other server time the portal shows
 * (`serverInstant`, the `serverTime` pipe): a zone-less stored value is UTC
 * and is shown on the reader's clock, which is the hospital's clock at the
 * desk.
 *
 * Every encounter `<option>` of the portal goes through this function
 * (`scripts/lib/encounter-option-label.test.mjs`).
 */
export function encounterOptionLabel(
  e: EncounterOptionSource,
  locale: string = currentLocale(),
): string {
  const when = e.encounterDate ? serverInstant(e.encounterDate) : null;
  const valid = when !== null && !Number.isNaN(when.getTime());
  const day = valid ? when.toLocaleDateString(locale) : '';
  const time = valid
    ? when.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', hour12: false })
    : '';
  return [day, time, e.departmentName].filter((part) => !!part).join(' · ');
}
