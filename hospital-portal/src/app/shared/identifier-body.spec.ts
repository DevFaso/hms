import { compactBody } from './identifier-body';

describe('compactBody', () => {
  it('keeps the typed values as typed', () => {
    expect(compactBody({ name: 'Awa Kaboré', phone: '+226 70 16 10 99' })).toEqual({
      name: 'Awa Kaboré',
      phone: '+226 70 16 10 99',
    });
  });

  it('drops empty, blank, null and missing values', () => {
    expect(
      compactBody({ name: '', dob: '   ', phone: null, email: undefined, mrn: 'MRN-1' }),
    ).toEqual({
      mrn: 'MRN-1',
    });
  });
});
