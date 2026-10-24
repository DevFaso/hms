import {
  fromServerDateTime,
  nowLocalDatetime,
  serverDateOrInstant,
  serverInstant,
  withServerDateTimes,
} from './date-utils';

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

describe('serverInstant (QA D777/D784)', () => {
  it('reads the zone-less UTC server time of the re-test as UTC', () => {
    expect(serverInstant('2026-10-24T15:33:17').getTime()).toBe(Date.UTC(2026, 9, 24, 15, 33, 17));
    expect(serverInstant('2026-10-24T15:33:17.123456').getTime()).toBe(
      Date.UTC(2026, 9, 24, 15, 33, 17, 123),
    );
  });

  it('keeps a stated zone and passes a bare calendar date as is', () => {
    expect(serverInstant('2026-10-24T15:33:17+02:00').getTime()).toBe(
      Date.UTC(2026, 9, 24, 13, 33, 17),
    );
    expect(serverInstant('2026-10-24T15:33:17Z').getTime()).toBe(Date.UTC(2026, 9, 24, 15, 33, 17));
    expect(serverInstant('2026-10-24').getTime()).toBe(new Date('2026-10-24').getTime());
  });

  it('gives an Invalid Date for an unreadable value, as new Date would', () => {
    expect(Number.isNaN(serverInstant('not a date').getTime())).toBeTrue();
  });
});

describe('serverDateOrInstant (#948 review)', () => {
  it('reads a bare calendar date as local midnight of that day, not UTC midnight', () => {
    const d = serverDateOrInstant('2026-10-24');
    expect([d.getFullYear(), d.getMonth(), d.getDate(), d.getHours()]).toEqual([2026, 9, 24, 0]);
  });

  it('reads a zone-less date and time as the UTC server time', () => {
    expect(serverDateOrInstant('2026-10-24T15:33:17').getTime()).toBe(
      Date.UTC(2026, 9, 24, 15, 33, 17),
    );
  });
});
