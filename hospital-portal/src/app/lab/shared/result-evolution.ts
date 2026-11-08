import { LabResultTrendPoint } from '../../services/lab.service';
import { serverInstant } from '../../shared/date-utils';

/** Plot exact observations only. Never infer a number from a comparator or convert units. */
export function evolutionSeries(history: LabResultTrendPoint[], unit: string) {
  return history
    .filter((point) => point.resultUnit?.trim() === unit && !!unit)
    .map((point) => ({
      ...point,
      time: serverInstant(point.resultDate).getTime(),
      value: /^[-+]?\d+(?:[.,]\d+)?$/.test(point.resultValue.trim())
        ? Number(point.resultValue.trim().replace(',', '.'))
        : NaN,
    }))
    .filter((point) => Number.isFinite(point.value) && Number.isFinite(point.time))
    .sort((a, b) => a.time - b.time);
}
