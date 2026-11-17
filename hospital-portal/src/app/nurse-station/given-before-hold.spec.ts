import { givenBeforeHoldRefusal } from './given-before-hold';

describe('givenBeforeHoldRefusal', () => {
  it('reads the refusal on its field, in either shape', () => {
    expect(
      givenBeforeHoldRefusal({
        status: 400,
        error: { fieldErrors: { givenBeforeHoldAt: 'Held' } },
      }),
    ).toBe('Held');
    expect(
      givenBeforeHoldRefusal({
        status: 400,
        error: { field: 'givenBeforeHoldAt', message: 'Held' },
      }),
    ).toBe('Held');
  });

  it('leaves any other refusal to its own handling', () => {
    expect(
      givenBeforeHoldRefusal({ status: 400, error: { fieldErrors: { earlyDoseReason: 'Soon' } } }),
    ).toBeNull();
    expect(givenBeforeHoldRefusal({ status: 409, error: { message: 'Withdrawn' } })).toBeNull();
    expect(givenBeforeHoldRefusal(null)).toBeNull();
  });
});
