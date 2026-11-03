import * as asNeeded from './as-needed-frequency';
import cases from './as-needed-frequency.cases.json';

// QA D1378 / D1360 (round 32): as-needed is the prescriber's explicit tick; the
// portal holds no reader of the frequency text any more (the round-30/31
// `isAsNeededFrequency` word list was the class that kept missing wordings).
describe('as-needed-frequency (QA D1378: the tick, never the text)', () => {
  it('exports only the interval bound, no frequency reader', () => {
    expect(Object.keys(asNeeded).sort()).toEqual(['PRN_MAX_INTERVAL_HOURS']);
    expect(asNeeded.PRN_MAX_INTERVAL_HOURS).toBe(72);
  });

  it('the shared fixture holds the round-32 wordings the server safety net must catch', () => {
    for (const wording of ['SOS', 'p.r.n.', 'sur demande', 'si precisa', 's/p', 'when necessary']) {
      expect(cases.conditional).withContext(wording).toContain(wording);
    }
    expect(cases.fixedSchedule).toContain('3 fois par jour');
  });
});
