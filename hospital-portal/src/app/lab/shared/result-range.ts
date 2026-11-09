import { LabResultResponse } from '../../services/lab.service';
import { exactObservation } from './result-evolution';

/** Display the server-selected range, already restated in the result's unit. */
export function resultRangePosition(result: LabResultResponse) {
  const range = result.gradedReferenceRange;
  const value = exactObservation(result.resultValue);
  if (!range || result.unitMismatch || result.gradeUnknown || !Number.isFinite(value)) return null;
  const { minValue: min, maxValue: max } = range;
  if (min == null || max == null || !Number.isFinite(min) || !Number.isFinite(max) || max <= min)
    return null;
  if ((range.unit ?? '').trim().toLowerCase() !== (result.resultUnit ?? '').trim().toLowerCase())
    return null;
  const width = max - min;
  if (!Number.isFinite(width)) return null;
  const state: 'low' | 'normal' | 'high' = value < min ? 'low' : value > max ? 'high' : 'normal';
  return {
    min,
    max,
    state,
    percent: Math.min(100, Math.max(0, (((value - min) / width + 1) * 100) / 3)),
    clipped: value < min - width || value > max + width,
  };
}
