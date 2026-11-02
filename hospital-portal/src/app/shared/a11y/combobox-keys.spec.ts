import { comboboxKey } from './combobox-keys';

describe('comboboxKey (QA D947)', () => {
  const key = (k: string) => new KeyboardEvent('keydown', { key: k, cancelable: true });

  it('walks the options with the arrows and wraps', () => {
    expect(comboboxKey(key('ArrowDown'), 3, -1).active).toBe(0);
    expect(comboboxKey(key('ArrowDown'), 3, 2).active).toBe(0);
    expect(comboboxKey(key('ArrowUp'), 3, 0).active).toBe(2);
  });

  it('picks the active option on Enter and on Tab, and closes on Escape', () => {
    expect(comboboxKey(key('Enter'), 3, 1)).toEqual({ active: 1, pick: true, close: true });
    expect(comboboxKey(key('Tab'), 3, 1)).toEqual({ active: 1, pick: true, close: true });
    expect(comboboxKey(key('Enter'), 3, -1).pick).toBeFalse();
    expect(comboboxKey(key('Escape'), 3, 1)).toEqual({ active: -1, pick: false, close: true });
  });
});
