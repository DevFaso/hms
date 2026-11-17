import { invoiceTone } from './invoice-tone';

describe('invoiceTone (QA D1071)', () => {
  it('never shows a cancelled invoice as paid', () => {
    expect(invoiceTone({ status: 'CANCELLED' }, false)).toEqual({
      icon: 'block',
      tone: 'cancelled',
    });
  });

  it('shows the check only for a paid invoice', () => {
    expect(invoiceTone({ status: 'PAID' }, false).icon).toBe('check_circle');
    expect(invoiceTone({ status: 'SENT' }, false).icon).not.toBe('check_circle');
    expect(invoiceTone({ status: 'PARTIALLY_PAID' }, false).icon).not.toBe('check_circle');
  });

  it('shows what is due as pending', () => {
    expect(invoiceTone({ status: 'SENT' }, true)).toEqual({ icon: 'pending', tone: 'due' });
  });

  it('says a folded copay was carried over', () => {
    expect(invoiceTone({ status: 'CANCELLED', creditedTo: 'CHW-2026-000020' }, false).tone).toBe(
      'credited',
    );
  });
});
