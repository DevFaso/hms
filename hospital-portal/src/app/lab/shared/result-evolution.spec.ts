import { evolutionSeries, exactObservation, observationDelta } from './result-evolution';

describe('result evolution', () => {
  const point = (resultValue: string, resultUnit = 'g/L', resultDate = '2026-11-08T09:00:00Z') => ({
    resultValue,
    resultUnit,
    resultDate,
  });
  it('keeps exact numbers in a single known unit and preserves correction flags', () => {
    const points = evolutionSeries(
      [
        point('1,2'),
        point('120', 'mg/dL'),
        point('>6.5'),
        point('Positive'),
        point('1.8', ''),
        point('NaN'),
        point('2', 'g/L', 'invalid'),
        { ...point('0.9', 'g/L', '2026-11-01T09:00:00Z'), corrected: true },
      ],
      'g/L',
    );
    expect(points.map((p) => p.value)).toEqual([0.9, 1.2]);
    expect(points[0].corrected).toBeTrue();
  });
  it('does not assume a unit for undocumented measurements', () => {
    expect(evolutionSeries([point('2', '')], '')).toEqual([]);
  });
  it('ignores empty observations without breaking the result panel', () => {
    expect(Number.isNaN(exactObservation(null))).toBeTrue();
    expect(Number.isNaN(exactObservation(undefined))).toBeTrue();
    expect(Number.isNaN(exactObservation(' '))).toBeTrue();
  });
  it('follows server decimal-comma semantics including trailing precision', () => {
    expect(exactObservation('1,200')).toBe(1.2);
    expect(exactObservation('250,000')).toBe(250);
    expect(Number.isNaN(exactObservation('1,200,000'))).toBeTrue();
  });
  it('does not calculate deltas across units, comparators or ambiguous dates', () => {
    const current = point('1.2');
    expect(observationDelta(current, point('120', 'mg/dL', '2026-11-01T09:00:00Z'))).toBeNull();
    expect(observationDelta(current, point('>1', 'g/L', '2026-11-01T09:00:00Z'))).toBeNull();
    expect(observationDelta(current, point('0.9'))).toBeNull();
  });
});
