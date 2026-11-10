import {
  ALLERGY_ACK_FIELD,
  allergyAcknowledgementFields,
  allergyAcknowledgementRefusal,
} from './allergy-acknowledgement';

describe('allergy acknowledgement (QA D1563 / D1555)', () => {
  const card = {
    summary: 'Allergy alert: Amoxicilline and penicillin',
    indicator: 'critical' as const,
    source: { label: 'HMS allergy check' },
    exceptionKey: 'allergy:abc123',
  };

  it('reads the refusal on its field, with the card and its token', () => {
    const refusal = allergyAcknowledgementRefusal({
      status: 400,
      error: {
        field: ALLERGY_ACK_FIELD,
        message: 'To dispense this order, acknowledge the allergy',
        fieldErrors: { [ALLERGY_ACK_FIELD]: 'To dispense this order, acknowledge the allergy' },
        cdsAdvisories: [card],
      },
    });
    expect(refusal?.why).toContain('acknowledge the allergy');
    expect(refusal?.cards.length).toBe(1);
    expect(refusal?.token).toBe('allergy:abc123');
    expect(allergyAcknowledgementFields(refusal!, '  rash only  ')).toEqual({
      allergyAcknowledgementReason: 'rash only',
      allergyAcknowledgementToken: 'allergy:abc123',
    });
  });

  it('leaves every other refusal to its own handling', () => {
    expect(
      allergyAcknowledgementRefusal({
        status: 400,
        error: { field: 'earlyDoseReason', message: 'x' },
      }),
    ).toBeNull();
    expect(
      allergyAcknowledgementRefusal({
        status: 409,
        error: { field: ALLERGY_ACK_FIELD, message: 'x' },
      }),
    ).toBeNull();
    expect(allergyAcknowledgementRefusal(null)).toBeNull();
  });
});
