/**
 * r63 D2312: warn at entry when a typed value is beyond the test's critical
 * (panic) limits, using the limits already configured on the test's reference
 * ranges — no new threshold. The server grades the saved result (by the
 * patient's sex and age band) and pages on release; this only tells the
 * technician before they press "Create".
 *
 * It is a warning, never a block, so it errs towards warning: a value beyond
 * the critical limits of ANY range stated in the value's unit (or with no
 * unit) warns, whichever age band or sex that range is for.
 */
export interface CriticalLimits {
  unit?: string | null;
  criticalLow?: number | null;
  criticalHigh?: number | null;
}

export type CriticalEntryVerdict = 'CRITICAL_LOW' | 'CRITICAL_HIGH' | null;

/** "6,9", " 6.9 ", "<2" or ">7" → the number (the bound for a comparator), or null for text. */
export function parseEnteredNumber(raw: string | null | undefined): number | null {
  const text = (raw ?? '').trim().replace(/^(<=|>=|<|>|≤|≥)\s*/, '');
  if (!/^-?\d+([.,]\d+)?$/.test(text)) {
    return null;
  }
  const value = Number(text.replace(',', '.'));
  return Number.isFinite(value) ? value : null;
}

function sameUnit(a: string | null | undefined, b: string | null | undefined): boolean {
  return (a ?? '').trim().toLowerCase() === (b ?? '').trim().toLowerCase();
}

/**
 * Whether `raw` is beyond the critical limits of a range in `unit`. A range
 * with no unit of its own is in the test's unit (`testUnit`), as the server
 * reads it; a blank `unit` is the test's unit too.
 */
export function criticalAtEntry(
  raw: string | null | undefined,
  ranges: readonly CriticalLimits[] | null | undefined,
  unit: string | null | undefined,
  testUnit?: string | null,
): CriticalEntryVerdict {
  const value = parseEnteredNumber(raw);
  if (value == null || !ranges?.length) {
    return null;
  }
  const entered = unit && unit.trim() ? unit : (testUnit ?? '');
  for (const range of ranges) {
    const rangeUnit = range.unit && range.unit.trim() ? range.unit : (testUnit ?? '');
    if (!sameUnit(rangeUnit, entered)) {
      continue;
    }
    if (range.criticalLow != null && value < range.criticalLow) {
      return 'CRITICAL_LOW';
    }
    if (range.criticalHigh != null && value > range.criticalHigh) {
      return 'CRITICAL_HIGH';
    }
  }
  return null;
}
