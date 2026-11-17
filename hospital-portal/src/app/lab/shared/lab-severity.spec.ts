import { labSeverityFlag, labSeverityTone, labReleaseNeedsReview } from './lab-severity';

describe('labSeverityFlag (QA D969)', () => {
  it('reads a positive qualitative answer as ABNORMAL, never "not graded"', () => {
    expect(labSeverityFlag({ severityFlag: 'ABNORMAL' })).toBe('ABNORMAL');
    expect(labSeverityTone(labSeverityFlag({ severityFlag: 'abnormal' }))).toBe('abnormal');
  });

  it('keeps every grade the server emits, and an unknown one as UNSPECIFIED', () => {
    for (const flag of ['CRITICAL', 'HIGH', 'LOW', 'NORMAL', 'UNSPECIFIED']) {
      expect(labSeverityFlag({ severityFlag: flag })).toBe(flag as never);
    }
    expect(labSeverityFlag({ severityFlag: 'W' })).toBe('UNSPECIFIED');
    expect(labSeverityFlag({ severityFlag: null })).toBe('UNSPECIFIED');
  });

  it('reads a result no range in its unit could grade as UNIT_MISMATCH', () => {
    expect(labSeverityFlag({ severityFlag: 'NORMAL', unitMismatch: true })).toBe('UNIT_MISMATCH');
  });

  it('QA D1394: a value no bound settled reads GRADE_UNKNOWN and needs a review', () => {
    expect(labSeverityFlag({ severityFlag: 'UNSPECIFIED', gradeUnknown: true })).toBe(
      'GRADE_UNKNOWN',
    );
    expect(labSeverityTone('GRADE_UNKNOWN')).toBe('ungraded');
    expect(labReleaseNeedsReview({ gradeUnknown: true })).toBeTrue();
    expect(labReleaseNeedsReview({ unitMismatch: true })).toBeTrue();
    expect(labReleaseNeedsReview({ severityFlag: 'UNSPECIFIED' })).toBeFalse();
  });
});
