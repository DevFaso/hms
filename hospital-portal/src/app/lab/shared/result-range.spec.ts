import { resultRangePosition } from './result-range';
import { LabResultResponse } from '../../services/lab.service';

describe('result reference position', () => {
  const result = (value: string, overrides: Partial<LabResultResponse> = {}) =>
    ({
      resultValue: value,
      resultUnit: 'cdx-unit',
      gradedReferenceRange: { minValue: 10, maxValue: 20, unit: 'cdx-unit' },
      ...overrides,
    }) as LabResultResponse;

  it('places low, normal and high values against the server-selected limits', () => {
    expect(resultRangePosition(result('5'))?.state).toBe('low');
    expect(resultRangePosition(result('15'))?.percent).toBeCloseTo(50);
    expect(resultRangePosition(result('15'))?.state).toBe('normal');
    expect(resultRangePosition(result('25'))?.state).toBe('high');
  });
  it('keeps both reference boundaries within normal', () => {
    expect(resultRangePosition(result('10'))?.state).toBe('normal');
    expect(resultRangePosition(result('10'))?.percent).toBeCloseTo(100 / 3);
    expect(resultRangePosition(result('20'))?.state).toBe('normal');
    expect(resultRangePosition(result('20'))?.percent).toBeCloseTo(200 / 3);
  });
  it('uses the backend-converted range rather than the first configured unit', () => {
    const row = result('520', {
      resultUnit: 'mg/dL',
      gradedReferenceRange: {
        minValue: 70,
        maxValue: 99,
        unit: 'mg/dL',
      } as LabResultResponse['gradedReferenceRange'],
      referenceRanges: [
        { minValue: 0.7, maxValue: 0.99, unit: 'g/L' },
      ] as LabResultResponse['referenceRanges'],
    });
    expect(resultRangePosition(row)).toEqual(
      jasmine.objectContaining({ min: 70, max: 99, state: 'high', percent: 100, clipped: true }),
    );
  });
  it('clips extreme values at the edge and explicitly identifies the clipped position', () => {
    expect(resultRangePosition(result('1000'))).toEqual(
      jasmine.objectContaining({ percent: 100, clipped: true, state: 'high' }),
    );
    expect(resultRangePosition(result('-1000'))).toEqual(
      jasmine.objectContaining({ percent: 0, clipped: true, state: 'low' }),
    );
  });
  it('does not invent a position for a comparator, text, mismatched unit or ungraded result', () => {
    for (const value of ['<10', '>20', 'Positive', '', 'NaN'])
      expect(resultRangePosition(result(value))).toBeNull();
    expect(resultRangePosition(result('15', { resultUnit: 'another-unit' }))).toBeNull();
    expect(resultRangePosition(result('15', { unitMismatch: true }))).toBeNull();
    expect(resultRangePosition(result('15', { gradeUnknown: true }))).toBeNull();
  });
  it('requires an applicable complete interval rather than selecting an arbitrary configured range', () => {
    expect(resultRangePosition(result('15', { gradedReferenceRange: null }))).toBeNull();
    for (const [minValue, maxValue] of [
      [null, 20],
      [10, null],
      [20, 10],
      [10, 10],
      [NaN, 20],
    ]) {
      expect(
        resultRangePosition(
          result('15', {
            gradedReferenceRange: {
              minValue,
              maxValue,
              unit: 'cdx-unit',
            } as LabResultResponse['gradedReferenceRange'],
          }),
        ),
      ).toBeNull();
    }
  });
});
