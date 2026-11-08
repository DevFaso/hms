import { evolutionSeries } from './result-evolution';

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
});
