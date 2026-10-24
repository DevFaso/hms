import { fromServerDateTime } from './date-utils';

describe('fromServerDateTime (QA D622)', () => {
  it('reads a zone-less server time as UTC', () => {
    expect(fromServerDateTime('2026-10-23T02:30:00')?.getTime()).toBe(Date.UTC(2026, 9, 23, 2, 30));
  });

  it('keeps an explicit zone', () => {
    expect(fromServerDateTime('2026-10-23T02:30:00+02:00')?.getTime()).toBe(
      Date.UTC(2026, 9, 23, 0, 30),
    );
  });

  it('is null for an empty or unparsable value', () => {
    expect(fromServerDateTime(null)).toBeNull();
    expect(fromServerDateTime('')).toBeNull();
    expect(fromServerDateTime('not a date')).toBeNull();
  });
});
