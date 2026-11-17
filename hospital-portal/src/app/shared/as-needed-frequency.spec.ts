import {
  frequencyReading,
  GRAMMAR_LINES,
  PRN_MAX_INTERVAL_HOURS,
  unTickedFrequencyVerdict,
} from './as-needed-frequency';
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

  it('asks about every wording that states no schedule, as the server does', () => {
    expect(cases.unreadable.length).toBeGreaterThan(10);
    for (const wording of cases.unreadable) {
      expect(unTickedFrequencyVerdict(wording)).withContext(wording).toBe('UNREADABLE');
      expect(frequencyReading(wording).kind).withContext(wording).toBe('UNREAD');
    }
  });

  it('QA D1419 / D1426: reads every accepted wording exactly as the server and its MAR do', () => {
    const readings = cases.readings as Record<string, string>;
    const accepted = cases.fixedSchedule.filter((w) => w.trim() !== '');
    expect(accepted.length).toBeGreaterThan(400);
    for (const wording of accepted) {
      const r = frequencyReading(wording);
      const read =
        r.kind +
        (r.intervalMinutes === undefined ? '' : `:${r.intervalMinutes}`) +
        (r.courseOffsets === undefined ? '' : `:[${r.courseOffsets.join(',')}]`) +
        (r.clockMinutes === undefined ? '' : `:[${r.clockMinutes.join(',')}]`);
      expect(read).withContext(wording).toBe(readings[wording]);
    }
  });

  it('QA D1419 / D1426: every filed wording has one outcome', () => {
    const outcomes = [cases.fixedSchedule, cases.conditional, cases.unreadable];
    for (const wording of Object.values(cases.filed).flat()) {
      expect(outcomes.filter((list) => list.includes(wording)).length)
        .withContext(wording)
        .toBe(1);
    }
  });

  it('QA D1419: an unknown word is never a condition', () => {
    expect(unTickedFrequencyVerdict('prendre 1 comprimé 3 fois par jour')).toBe('FIXED');
    expect(unTickedFrequencyVerdict('1 cp par jour pour HTA')).toBe('UNREADABLE');

    expect(unTickedFrequencyVerdict('pour HTA')).toBe('UNREADABLE');
    expect(unTickedFrequencyVerdict('toutes les 6 h pour douleur')).toBe('CONDITIONAL');
  });

  it('never refuses a choice of the portal dropdown (except PRN, which ticks the item)', () => {
    const offered = FREQUENCY_PRESETS.map((p) => p.value).filter((v) => v !== 'PRN');
    expect(offered).toEqual(cases.dropdown);
    for (const wording of offered) {
      expect(unTickedFrequencyVerdict(wording)).withContext(wording).toBe('FIXED');
    }
  });

  const read = (wording: string): string => {
    const r = frequencyReading(wording);
    return (
      r.kind +
      (r.intervalMinutes === undefined ? '' : `:${r.intervalMinutes}`) +
      (r.courseOffsets === undefined ? '' : `:[${r.courseOffsets.join(',')}]`) +
      (r.clockMinutes === undefined ? '' : `:[${r.clockMinutes.join(',')}]`)
    );
  };

  it('QA D1452: reads every form of the grammar as the fixture states, as the server does', () => {
    const forms = Object.entries(cases.grammarForms as Record<string, string>);
    expect(forms.length).toBeGreaterThan(200);
    for (const [wording, reading] of forms) {
      expect(read(wording)).withContext(wording).toBe(reading);
      expect(unTickedFrequencyVerdict(wording)).withContext(wording).toBe('FIXED');
    }
  });

  it('QA D1452-D1459: a form with anything else beside it is never a schedule, as on the server', () => {
    let counted = 0;
    for (const form of Object.keys(cases.grammarForms)) {
      for (const clause of cases.matrixClauses) {
        for (const wording of [`${clause} ${form}`, `${form} ${clause}`]) {
          // One check per form below: thousands of expectations would flood the reporter.
          if (frequencyReading(wording).kind !== 'UNREAD') fail(`read as a schedule: ${wording}`);
          if (unTickedFrequencyVerdict(wording) === 'FIXED') fail(`accepted: ${wording}`);
          counted++;
        }
      }
    }
    expect(counted).toBeGreaterThan(2500);
  });

  it('QA D1486, D1487, D1512: every number slot of the grammar has its bound, as on the server', () => {
    const bounds = cases.slotBounds as Record<string, { in: string[]; out: string[] }>;
    const sample = cases.slotSample as Record<string, string>;
    let inRange = 0;
    let outOfRange = 0;
    for (const { meaning, tokens } of GRAMMAR_LINES) {
      if (!tokens.includes('#')) continue;
      const instance = (n: string): string =>
        tokens.map((t) => (t === '#' ? n : (sample[t] ?? t))).join(' ');
      const bound = bounds[meaning];
      if (!bound) fail(`no bound for ${meaning}: ${tokens.join(' ')}`);
      for (const n of bound.in) {
        if (frequencyReading(instance(n)).kind === 'UNREAD') fail(`asked: ${instance(n)}`);
        inRange++;
      }
      for (const n of bound.out) {
        if (frequencyReading(instance(n)).kind !== 'UNREAD') fail(`read: ${instance(n)}`);
        if (unTickedFrequencyVerdict(instance(n)) !== 'UNREADABLE')
          fail(`not asked: ${instance(n)}`);
        outOfRange++;
      }
    }
    expect(inRange).toBeGreaterThan(150);
    expect(outOfRange).toBeGreaterThan(200);
  });

  it('QA D1491: nothing written is unread, so a course never ends on it; the form refuses it', () => {
    for (const blank of [null, undefined, '', '   ', '\u00a0', '.', '-', '()', '\u200b']) {
      expect(frequencyReading(blank).kind).toBe('UNREAD');
      expect(unTickedFrequencyVerdict(blank)).toBe('FIXED');
    }
  });

  it('QA D1460: "dormir" and an accented "si" are no conditions; "si" with no accent is', () => {
    expect(unTickedFrequencyVerdict('avant de dormir')).toBe('FIXED');
    expect(read('un día sí y otro no')).toBe('REPEATING:2880');
    expect(unTickedFrequencyVerdict('toutes les 8 h pour dormir')).toBe('UNREADABLE');
    expect(unTickedFrequencyVerdict('una vez al día un día sí y otro no')).toBe('UNREADABLE');
    expect(unTickedFrequencyVerdict('cada 6 horas si dolor')).toBe('CONDITIONAL');
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
