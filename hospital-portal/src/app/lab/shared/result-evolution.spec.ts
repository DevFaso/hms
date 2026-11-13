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
    // 120 mg/dL is 1.2 g/L exactly: it joins the series, marked converted, its stored value kept.
    expect(points.map((p) => p.value)).toEqual([0.9, 1.2, 1.2]);
    expect(points[0].corrected).toBeTrue();
    const converted = points.find((p) => p.converted)!;
    expect(converted.resultValue).toBe('120');
    expect(converted.originalUnit).toBe('mg/dL');
  });
  it('merges glucose in g/L, mg/dL and mmol/L into one series in the display unit', () => {
    const points = evolutionSeries(
      [
        point('0.99', 'g/L', '2026-11-01T09:00:00Z'),
        point('110', 'mg/dL', '2026-11-02T09:00:00Z'),
        point('5,5', 'mmol/L', '2026-11-03T09:00:00Z'),
      ],
      'g/L',
      'GLUCOSE',
    );
    expect(points.map((p) => p.value)).toEqual([0.99, 1.1, 0.9909]);
    expect(points.map((p) => p.converted)).toEqual([false, true, true]);
    expect(points.map((p) => p.originalUnit)).toEqual(['g/L', 'mg/dL', 'mmol/L']);
  });
  it('keeps a molar unit apart when the analyte has no listed molar mass', () => {
    const history = [
      point('80', 'µmol/L', '2026-11-01T09:00:00Z'),
      point('9', 'mg/L', '2026-11-02T09:00:00Z'),
      point('0.09', 'mmol/L', '2026-11-03T09:00:00Z'),
    ];
    expect(evolutionSeries(history, 'µmol/L').map((p) => p.value)).toEqual([80, 90]);
    expect(evolutionSeries(history, 'mg/L').map((p) => p.value)).toEqual([9]);
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
