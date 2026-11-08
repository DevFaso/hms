import { LabResultTrendPoint } from '../../services/lab.service';
import { serverInstant } from '../../shared/date-utils';

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

/** Plot exact observations only. Never infer a number from a comparator or convert units. */
export function evolutionSeries(history: LabResultTrendPoint[], unit: string) {
  return history
    .filter((point) => point.resultUnit?.trim() === unit && !!unit)
    .map((point) => ({
      ...point,
      time: serverInstant(point.resultDate).getTime(),
      value: exactObservation(point.resultValue),
    }))
    .filter((point) => Number.isFinite(point.value) && Number.isFinite(point.time))
    .sort((a, b) => a.time - b.time);
}
