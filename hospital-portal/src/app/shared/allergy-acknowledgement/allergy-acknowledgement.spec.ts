import { ALLERGY_HOLD_FIELD, allergyHoldRefusal } from './allergy-acknowledgement';

describe('allergy hold (QA D1563 / D1555, Policy 4)', () => {
  const card = {
    summary: 'Allergy alert: Amoxicilline and penicillin',
    indicator: 'critical' as const,
    source: { label: 'HMS allergy check' },
    exceptionKey: 'allergy:abc123',
  };

  it('reads the hold on its field, with the card and who was notified', () => {
    const hold = allergyHoldRefusal({
      status: 400,
      error: {
        field: ALLERGY_HOLD_FIELD,
        message: 'Allergy conflict awaiting prescriber review. Notified: Awa Traore.',
        fieldErrors: {
          [ALLERGY_HOLD_FIELD]:
            'Allergy conflict awaiting prescriber review. Notified: Awa Traore.',
        },
        cdsAdvisories: [card],
      },
    });
    expect(hold?.why).toContain('awaiting prescriber review');
    expect(hold?.why).toContain('Awa Traore');
    expect(hold?.cards.length).toBe(1);
  });

  it('is not the old staff acknowledgement field, and leaves every other refusal to its own handling', () => {
    expect(ALLERGY_HOLD_FIELD).not.toBe('allergyAcknowledgementReason');
    expect(
      allergyHoldRefusal({
        status: 400,
        error: { field: 'allergyAcknowledgementReason', message: 'x' },
      }),
    ).toBeNull();
    expect(
      allergyHoldRefusal({
        status: 409,
        error: { field: ALLERGY_HOLD_FIELD, message: 'x' },
      }),
    ).toBeNull();
    expect(allergyHoldRefusal(null)).toBeNull();
  });
});
