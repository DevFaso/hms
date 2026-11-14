import { LabResultTrendPoint } from '../../services/lab.service';
import { serverInstant } from '../../shared/date-utils';
import { KnownAnalyte, convertValue, unitConversion } from './lab-unit-conversion';

/** Match the server's decimal-comma contract; grouping separators are not part of it. */
export function exactObservation(value: string | null | undefined): number {
  const raw = (value ?? '').trim();
  return /^[-+]?\d+(?:[.,]\d+)?$/.test(raw) ? Number(raw.replace(',', '.')) : NaN;
}

/** Adjacent chronological measurements can only have a delta in the same documented unit. */
export function observationDelta(
  current: LabResultTrendPoint,
  previous?: LabResultTrendPoint,
): number | null {
  if (
    !previous ||
    !current.resultUnit?.trim() ||
    current.resultUnit.trim() !== previous.resultUnit?.trim()
  )
    return null;
  const time = serverInstant(current.resultDate).getTime();
  const priorTime = serverInstant(previous.resultDate).getTime();
  const value = exactObservation(current.resultValue),
    prior = exactObservation(previous.resultValue);
  return Number.isFinite(time) &&
    Number.isFinite(priorTime) &&
    time > priorTime &&
    Number.isFinite(value) &&
    Number.isFinite(prior)
    ? value - prior
    : null;
}

/**
 * Plot exact observations only, never a number inferred from a comparator. A
 * point recorded in another unit of the same quantity is converted into `unit`
 * when {@link unitConversion} knows an exact factor (or the analyte's listed
 * molar mass); it keeps its stored value and unit and is marked `converted`.
 * A point that cannot be converted is left out of this series.
 */
export function evolutionSeries(
  history: LabResultTrendPoint[],
  unit: string,
  analyte: KnownAnalyte | null = null,
) {
  const target = unit?.trim();
  if (!target) return [];
  return history
    .map((point) => {
      const original = point.resultUnit?.trim() ?? '';
      const stored = exactObservation(point.resultValue);
      const conversion = original === target ? null : unitConversion(original, target, analyte);
      if (original !== target && !conversion) return null;
      return {
        ...point,
        time: serverInstant(point.resultDate).getTime(),
        value: conversion ? convertValue(stored, conversion) : stored,
        converted: !!conversion,
        originalUnit: original,
      };
    })
    .filter(
      (point): point is NonNullable<typeof point> =>
        !!point && Number.isFinite(point.value) && Number.isFinite(point.time),
    )
    .sort((a, b) => a.time - b.time);
}

/**
 * r44: a y-axis with round ticks. Steps are 1, 2 or 5 times a power of ten,
 * chosen so about `target` intervals cover [min, max]; the axis ends on ticks
 * ("1,0 – 1,4", never "0,952 – 1,288"). `decimals` is the precision the step
 * needs, so every tick prints the same way.
 */
export function niceAxis(min: number, max: number, target = 4) {
  if (!Number.isFinite(min) || !Number.isFinite(max)) return null;
  if (max < min) [min, max] = [max, min];
  if (max === min) {
    const pad = Math.abs(max) * 0.1 || 1;
    min -= pad;
    max += pad;
  }
  const intervals = Number.isFinite(target) ? Math.min(100, Math.max(1, target)) : 4;
  const raw = (max - min) / intervals;
  if (!Number.isFinite(raw) || raw <= 0) return null;
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const fraction = raw / magnitude;
  const step = (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * magnitude;
  if (!Number.isFinite(step) || step <= 0) return null;
  const decimals = Math.max(0, -Math.floor(Math.log10(step) + 1e-9));
  // The chart's number formatter supports at most 20 decimal places.
  if (decimals > 20) return null;
  // Snap away binary noise (0.30000000000000004) at well below the step.
  const factor = 10 ** Math.min(15, decimals + 2);
  const round = (v: number) => {
    const scaled = v * factor;
    // Keep large finite endpoints and sub-factor values rather than overflowing
    // or erasing them while removing ordinary binary rounding noise.
    return Number.isFinite(scaled) && Math.abs(v) >= 1 / factor ? Math.round(scaled) / factor : v;
  };
  const lo = round(Math.floor(min / step + 1e-9) * step);
  const hi = round(Math.ceil(max / step - 1e-9) * step);
  if (!Number.isFinite(lo) || !Number.isFinite(hi) || hi <= lo) return null;
  const count = Math.round((hi - lo) / step) + 1;
  if (!Number.isFinite(count) || count < 2 || count > 102) return null;
  const ticks: number[] = [];
  for (let i = 0; i < count; i++) {
    const tick = round(lo + i * step);
    if (!Number.isFinite(tick) || (i > 0 && tick <= ticks[i - 1])) return null;
    ticks.push(tick);
  }
  return { min: lo, max: hi, step, decimals, ticks };
}

/**
 * r44: the observation just before the current result (the latest one dated
 * strictly earlier), from the history the server sent, and the change from it.
 * The change is withheld across units or non-exact numbers. When the history
 * holds a correction, the whole comparison is withheld: its DTO cannot tell a
 * superseded version apart from a separate measurement. The audit table still
 * shows the recorded observations with correction badges.
 */
export function previousObservation(
  history: LabResultTrendPoint[],
  current: { resultDate: string; resultValue: string; resultUnit: string; corrected?: boolean },
): { point: LabResultTrendPoint; delta: number | null } | null {
  // The trend DTO has no order/result identity to distinguish a superseded row.
  if (current.corrected || history.some((p) => p.corrected)) return null;
  const now = serverInstant(current.resultDate).getTime();
  if (!Number.isFinite(now)) return null;
  let best: LabResultTrendPoint | null = null;
  let bestTime = -Infinity;
  for (const point of history) {
    const time = serverInstant(point.resultDate).getTime();
    if (Number.isFinite(time) && time < now && time > bestTime) {
      best = point;
      bestTime = time;
    }
  }
  if (!best) return null;
  const delta = observationDelta(current as LabResultTrendPoint, best);
  return { point: best, delta };
}

/** The decimals a difference of two recorded values is shown with: the finer of the two. */
export function deltaPrecision(...values: (string | null | undefined)[]): number {
  return Math.min(20, Math.max(0, ...values.map((v) => (v?.trim().split(/[.,]/)[1] ?? '').length)));
}
