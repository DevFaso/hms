import { formatLabNumber } from './lab-value.pipe';

/** The limits of a reference range, as every lab screen prints them. */
export interface ReferenceRangeLimits {
  minValue: number | null;
  maxValue: number | null;
  unit: string | null;
}

/**
 * One way to print a reference range ("0,7 – 1,1 g/L", "≥ 4", "≤ 10"), shared
 * by the lab-results page and the lab requests drawer so the two never print
 * the same range two ways. QA D1002: the limits in the reader's number format.
 * A range with neither limit prints "—".
 */
export function formatLabReferenceRange(range: ReferenceRangeLimits): string {
  const min = range.minValue;
  const max = range.maxValue;
  const unit = range.unit ? ` ${range.unit}` : '';
  if (min != null && max != null) {
    return `${formatLabNumber(min)} – ${formatLabNumber(max)}${unit}`;
  }
  if (min != null) {
    return `≥ ${formatLabNumber(min)}${unit}`;
  }
  if (max != null) {
    return `≤ ${formatLabNumber(max)}${unit}`;
  }
  return '—';
}
