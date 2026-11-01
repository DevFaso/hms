import { refusalReason, refusedFieldReasons } from './refusal-reason';

describe('refusalReason', () => {
  it('shows a 4xx reason, field reasons first', () => {
    expect(refusalReason({ status: 409, error: { message: 'Déjà enregistré' } })).toBe(
      'Déjà enregistré',
    );
    expect(
      refusalReason({
        status: 400,
        error: {
          message: 'Validation Failed',
          fieldErrors: { studyType: 'ne doit pas être vide' },
        },
      }),
    ).toBe('ne doit pas être vide');
  });

  it('hides a 5xx body, but a 503 says why the service is unavailable (review of #1000)', () => {
    expect(
      refusalReason({ status: 500, error: { message: 'NullPointerException at …' } }),
    ).toBeNull();
    expect(
      refusalReason({
        status: 503,
        error: { message: 'Mode lecture seule : maintenance en cours.' },
      }),
    ).toBe('Mode lecture seule : maintenance en cours.');
    expect(refusalReason({ error: { message: 'no status' } })).toBeNull();
  });
});

describe('refusedFieldReasons (QA D1156)', () => {
  it('pairs each known field with the server reason for it', () => {
    const err = {
      status: 400,
      error: { fieldErrors: { studyType: 'ne doit pas être vide', other: 'x', patientId: '' } },
    };
    expect(
      refusedFieldReasons(err, { studyType: 'img-studyType', patientId: 'img-patient' }),
    ).toEqual([['img-studyType', 'ne doit pas être vide']]);
    expect(refusedFieldReasons({ status: 500 }, { studyType: 'img-studyType' })).toEqual([]);
  });
});
