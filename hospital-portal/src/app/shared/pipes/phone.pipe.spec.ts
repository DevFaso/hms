import { NO_BREAK_SPACE, PhonePipe } from './phone.pipe';

/** QA D2799 / D2794 (r65): « +22670000109 » read as « +226 70 00 01 09 ». */
describe('PhonePipe (QA D2799)', () => {
  const pipe = new PhonePipe();
  const grouped = (...groups: string[]) => groups.join(NO_BREAK_SPACE);

  it('groups a Burkina Faso E.164 number as the calling code and four pairs', () => {
    expect(pipe.transform('+22670000109')).toBe(grouped('+226', '70', '00', '01', '09'));
    expect(pipe.transform(' +22670001001 ')).toBe(grouped('+226', '70', '00', '10', '01'));
  });

  it('never wraps inside a number: the groups are joined by no-break spaces', () => {
    const shown = pipe.transform('+22670000109');
    expect(NO_BREAK_SPACE.codePointAt(0)).toBe(0xa0);
    expect(shown).not.toContain(' ');
    expect(shown.split(NO_BREAK_SPACE).length).toBe(5);
  });

  it('leaves any other value exactly as stored', () => {
    for (const raw of ['+33612345678', '70000109', '+2267000010', '+226700001099', 'n/a', '']) {
      expect(pipe.transform(raw)).toBe(raw);
    }
  });

  it('prints nothing for a missing number, so `| dash` still applies', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
  });
});
