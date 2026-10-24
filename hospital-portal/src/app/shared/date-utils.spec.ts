import { fromServerDateTime, nowLocalDatetime, withServerDateTimes } from './date-utils';

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

describe('withServerDateTimes (#941 review)', () => {
  it('sends a wall-clock "now" as the UTC time the server checks it against', () => {
    // A birth charted "now" at 10:00 on a UTC+1 workstation is 09:00 UTC;
    // sent as 10:00 the server (UTC) refused it as the future.
    const now = new Date(Date.UTC(2026, 9, 24, 9, 0));
    const sent = withServerDateTimes({ birthDateTime: nowLocalDatetime(now), notes: 'x' }, [
      'birthDateTime',
    ]);
    expect(sent.birthDateTime).toBe('2026-10-24T09:00:00');
    expect(sent.notes).toBe('x');
  });

  it('leaves empty fields alone', () => {
    expect(withServerDateTimes({ birthDateTime: '' }, ['birthDateTime']).birthDateTime).toBe('');
  });
});
