import { personNameMatches, phoneMatches, phoneSearchKey, textMatches } from './person-search';

describe('person-search (QA D1019, D1020)', () => {
  const pelagie = { firstName: 'Pélagie', middleName: 'Wendkouni', lastName: 'Kiemdé' };

  it('finds a name typed without its accents', () => {
    expect(personNameMatches(pelagie, 'Kiemde')).toBeTrue();
    expect(personNameMatches(pelagie, 'pelagie')).toBeTrue();
    expect(personNameMatches({ firstName: 'Aïcha', lastName: 'Sawadogo' }, 'Aicha')).toBeTrue();
  });

  it('finds the full name, in either order, with or without the middle name', () => {
    expect(personNameMatches(pelagie, 'Pélagie Kiemdé')).toBeTrue();
    expect(personNameMatches(pelagie, 'kiemde  pelagie')).toBeTrue();
    expect(personNameMatches(pelagie, 'Pelagie Wendkouni Kiemde')).toBeTrue();
    expect(personNameMatches(pelagie, 'Adjara Nébié')).toBeFalse();
  });

  it('finds a number however it is written', () => {
    for (const typed of ['65 11 22 33', '65112233', '+226 65 11 22 33', '0022665112233', '6511']) {
      expect(phoneMatches('+22665112233', typed)).withContext(typed).toBeTrue();
    }
    expect(phoneMatches('+22665112233', '75 40 50 60')).toBeFalse();
    expect(phoneMatches('+22665112233', '22')).withContext('too short to be a phone search').toBeFalse();
    expect(phoneSearchKey('+226 65 11 22 33')).toBe('65112233');
  });

  it('an empty term matches everything, a missing value nothing else', () => {
    expect(textMatches(null, '')).toBeTrue();
    expect(textMatches(null, 'x')).toBeFalse();
  });
});
