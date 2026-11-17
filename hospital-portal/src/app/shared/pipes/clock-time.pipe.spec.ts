import { ClockTimePipe } from './clock-time.pipe';

describe('ClockTimePipe (QA D830)', () => {
  const pipe = new ClockTimePipe();

  it('drops the seconds the API sends with a time of day', () => {
    expect(pipe.transform('09:00:00')).toBe('09:00');
    expect(pipe.transform('14:30:00.000')).toBe('14:30');
    expect(pipe.transform('9:05:00')).toBe('09:05');
  });

  it('keeps a time already in hours and minutes', () => {
    expect(pipe.transform('09:00')).toBe('09:00');
  });

  it('returns anything else unchanged, and nothing for no value', () => {
    expect(pipe.transform('2026-10-26T09:00:00')).toBe('2026-10-26T09:00:00');
    expect(pipe.transform('—')).toBe('—');
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
  });
});
