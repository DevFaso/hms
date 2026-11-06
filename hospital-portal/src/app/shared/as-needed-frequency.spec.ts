import { PRN_MAX_INTERVAL_HOURS, unTickedFrequencyVerdict } from './as-needed-frequency';
import cases from './as-needed-frequency.cases.json';
import { FREQUENCY_PRESETS } from '../admin/order-sets/order-set-edit.component';

// QA D1378 / D1360 (round 32): as-needed is the prescriber's explicit tick.
// QA D1391 / D1395 (round 33): the one thing read from the text is the server's
// rule on an order NOT ticked, mirrored here for a hint; the fixture is shared
// with FrequencyWordingTest, so both sides answer every wording the same.
describe('as-needed-frequency (QA D1391 / D1395: the mirror of the server rule)', () => {
  it('holds the interval bound', () => {
    expect(PRN_MAX_INTERVAL_HOURS).toBe(72);
  });

  it('accepts every fixed schedule of the fixture, as the server does', () => {
    expect(cases.fixedSchedule.length).toBeGreaterThan(100);
    for (const wording of cases.fixedSchedule) {
      expect(unTickedFrequencyVerdict(wording)).withContext(wording).toBe('FIXED');
    }
  });

  it('refuses every conditional wording of the fixture, as the server does', () => {
    for (const wording of cases.conditional) {
      expect(unTickedFrequencyVerdict(wording)).withContext(wording).toBe('CONDITIONAL');
    }
    for (const wording of cases.asNeeded) {
      expect(unTickedFrequencyVerdict(wording)).withContext(wording).toBe('CONDITIONAL');
    }
  });

  it('refuses every wording that states no schedule, as the server does', () => {
    expect(cases.unreadable.length).toBeGreaterThan(10);
    for (const wording of cases.unreadable) {
      expect(unTickedFrequencyVerdict(wording)).withContext(wording).toBe('UNREADABLE');
    }
  });

  it('never refuses a choice of the portal dropdown (except PRN, which ticks the item)', () => {
    const offered = FREQUENCY_PRESETS.map((p) => p.value).filter((v) => v !== 'PRN');
    expect(offered).toEqual(cases.dropdown);
    for (const wording of offered) {
      expect(unTickedFrequencyVerdict(wording)).withContext(wording).toBe('FIXED');
    }
  });

  it('reads nothing written as nothing to refuse', () => {
    for (const blank of [null, undefined, '', '   ']) {
      expect(unTickedFrequencyVerdict(blank)).toBe('FIXED');
    }
  });

  it('the shared fixture holds the round-32 wordings the server safety net must catch', () => {
    for (const wording of ['SOS', 'p.r.n.', 'sur demande', 'si precisa', 's/p', 'when necessary']) {
      expect(cases.conditional).withContext(wording).toContain(wording);
    }
    expect(cases.fixedSchedule).toContain('3 fois par jour');
  });
});
