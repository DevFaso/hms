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
  const raw = (max - min) / Math.max(1, target);
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const fraction = raw / magnitude;
  const step = (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * magnitude;
  const decimals = Math.max(0, -Math.floor(Math.log10(step) + 1e-9));
  // Snap away binary noise (0.30000000000000004) at well below the step.
  const factor = 10 ** Math.min(15, decimals + 2);
  const round = (v: number) => Math.round(v * factor) / factor;
  const lo = round(Math.floor(min / step + 1e-9) * step);
  const hi = round(Math.ceil(max / step - 1e-9) * step);
  const ticks: number[] = [];
  for (let i = 0; lo + i * step <= hi + step / 2; i++) ticks.push(round(lo + i * step));
  return { min: lo, max: hi, step, decimals, ticks };
}

/**
 * r44: the observation just before the current result (the latest one dated
 * strictly earlier), from the history the server sent, and the change from it.
 * The change is null (withheld) when the two are in different units, either
 * is not an exact number, or the history holds a correction: a superseded
 * version cannot be told apart there, so no difference is drawn from it (the
 * evolution table withholds its deltas on the same rule).
 */
export function previousObservation(
  history: LabResultTrendPoint[],
  current: { resultDate: string; resultValue: string; resultUnit: string },
): { point: LabResultTrendPoint; delta: number | null } | null {
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
  const corrected = history.some((p) => p.corrected);
  const delta = corrected ? null : observationDelta(current as LabResultTrendPoint, best);
  return { point: best, delta };
}

/** The decimals a difference of two recorded values is shown with: the finer of the two. */
export function deltaPrecision(...values: (string | null | undefined)[]): number {
  return Math.min(20, Math.max(0, ...values.map((v) => (v?.trim().split(/[.,]/)[1] ?? '').length)));
}
