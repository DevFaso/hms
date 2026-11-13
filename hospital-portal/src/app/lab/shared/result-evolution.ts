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
