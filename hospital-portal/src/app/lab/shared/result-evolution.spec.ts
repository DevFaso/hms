import {
  evolutionSeries,
  exactObservation,
  niceAxis,
  observationDelta,
  previousObservation,
} from './result-evolution';

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

  describe('niceAxis (r44)', () => {
    it('rounds the review case to tenths', () => {
      const axis = niceAxis(0.952, 1.288)!;
      expect(axis.decimals).toBe(1);
      expect(axis.ticks).toEqual([0.9, 1, 1.1, 1.2, 1.3]);
    });

    it('covers both ends with 1, 2 or 5 steps at any magnitude', () => {
      for (const [lo, hi] of [
        [0.7, 1.32],
        [3.9, 27.8],
        [120, 455],
        [0.0011, 0.0019],
        [5, 5],
      ]) {
        const axis = niceAxis(lo, hi)!;
        expect(axis.min).toBeLessThanOrEqual(lo);
        expect(axis.max).toBeGreaterThanOrEqual(hi);
        const mantissa = axis.step / 10 ** Math.floor(Math.log10(axis.step) + 1e-9);
        expect([1, 2, 5]).toContain(Math.round(mantissa));
        expect(axis.ticks.length).toBeLessThanOrEqual(8);
      }
    });

    it('has no axis for a non-finite bound', () => {
      expect(niceAxis(NaN, 1)).toBeNull();
    });
  });

  describe('previousObservation (r44)', () => {
    const current = { resultValue: '1.05', resultUnit: 'g/L', resultDate: '2026-11-13T09:00:00Z' };

    it('takes the latest observation strictly before the result, and the change from it', () => {
      const prev = previousObservation(
        [
          point('1.05', 'g/L', '2026-11-13T09:00:00Z'),
          point('1.32', 'g/L', '2026-11-12T09:00:00Z'),
          point('0.9', 'g/L', '2026-11-01T09:00:00Z'),
        ],
        current,
      )!;
      expect(prev.point.resultValue).toBe('1.32');
      expect(prev.delta).toBeCloseTo(-0.27, 10);
    });

    it('withholds the change across units or in a corrected history', () => {
      expect(
        previousObservation([point('7.3', 'mmol/L', '2026-11-12T09:00:00Z')], current)!.delta,
      ).toBeNull();
      expect(
        previousObservation(
          [{ ...point('1.32', 'g/L', '2026-11-12T09:00:00Z'), corrected: true }],
          current,
        )!.delta,
      ).toBeNull();
    });

    it('has no previous value for a first result', () => {
      expect(
        previousObservation([point('1.05', 'g/L', '2026-11-13T09:00:00Z')], current),
      ).toBeNull();
    });
  });
});
