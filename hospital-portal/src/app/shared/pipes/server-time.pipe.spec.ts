import { ServerTimePipe } from './server-time.pipe';

describe('ServerTimePipe (QA D436)', () => {
  const pipe = new ServerTimePipe();

  it('marks a zone-less server timestamp as UTC', () => {
    expect(pipe.transform('2026-10-20T09:15:00')).toBe('2026-10-20T09:15:00Z');
    expect(pipe.transform('2026-10-20T09:15:00.123456')).toBe('2026-10-20T09:15:00.123456Z');
    expect(pipe.transform('2026-10-20T09:15')).toBe('2026-10-20T09:15Z');
  });

  it('leaves values that already carry a zone, plain dates and non-strings alone', () => {
    expect(pipe.transform('2026-10-20T09:15:00Z')).toBe('2026-10-20T09:15:00Z');
    expect(pipe.transform('2026-10-20T09:15:00+01:00')).toBe('2026-10-20T09:15:00+01:00');
    expect(pipe.transform('2026-10-20')).toBe('2026-10-20');
    expect(pipe.transform(null)).toBeNull();
    expect(pipe.transform(undefined)).toBeUndefined();
  });

  it('gives the same instant as the UTC wall time', () => {
    const iso = pipe.transform('2026-10-20T09:15:00');
    expect(new Date(iso).getTime()).toBe(Date.UTC(2026, 9, 20, 9, 15, 0));
  });
});
