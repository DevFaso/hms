import {
  birthDateMatches,
  exactIdentifierFirst,
  patientMatchesTerm,
  personNameMatches,
  phoneMatches,
  phoneSearchKey,
  rankPeopleByName,
  textMatches,
} from './person-search';

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
    expect(phoneMatches('+22665112233', '22'))
      .withContext('too short to be a phone search')
      .toBeFalse();
    expect(phoneSearchKey('+226 65 11 22 33')).toBe('65112233');
  });

  it('an empty term matches everything, a missing value nothing else', () => {
    expect(textMatches(null, '')).toBeTrue();
    expect(textMatches(null, 'x')).toBeFalse();
  });

  it('QA D1218: a term with letters (an MRN) is never a phone search', () => {
    expect(phoneMatches('+22670004101', 'CHW0410')).toBeFalse();
    expect(phoneMatches('+22670004101', '0410')).toBeTrue();
    expect(phoneMatches('+22670004101', '+226 70-00.41(01)')).toBeTrue();
  });

  it('QA D1218: the exact identifier comes first, the others keep their order', () => {
    const rows = [{ mrn: 'CHW04100' }, { mrn: 'B' }, { mrn: 'chw0410' }];
    expect(exactIdentifierFirst(rows, 'CHW0410', (r) => r.mrn).map((r) => r.mrn)).toEqual([
      'chw0410',
      'CHW04100',
      'B',
    ]);
    expect(exactIdentifierFirst(rows, 'none', (r) => r.mrn)).toEqual(rows);
  });
});

describe('rankPeopleByName (QA D1239)', () => {
  const sawadogos = [
    'Boukaré',
    'Djénéba',
    'Boureima',
    'Rakiéta',
    'Bintou',
    'Salamata',
    'Ismaël',
    'Aïssata',
  ].map((firstName, i) => ({ id: `s${i}`, firstName, lastName: 'Sawadogo', mrn: `CHW10${i}` }));
  const awa = { id: 'awa', firstName: 'Awa', lastName: 'Kaboré', mrn: 'CHW7068' };

  it('offers Awa Kaboré for "Awa" ahead of eight Sawadogos that only contain it', () => {
    const ranked = rankPeopleByName([...sawadogos, awa], 'Awa');
    expect(ranked[0].id).toBe('awa');
    expect(ranked.slice(0, 8).map((p) => p.id)).toContain('awa');
  });

  it('ranks exact full name or MRN, then an exact name, then a name start, then a word start, then the rest; equals keep their order', () => {
    const rows = [
      { id: 'contains', firstName: 'Sawa', lastName: 'Dogo' },
      { id: 'word', firstName: 'Marie Awa', lastName: 'Zongo' },
      { id: 'start', firstName: 'Awaïs', lastName: 'Ilboudo' },
      { id: 'exact', firstName: 'Awa', lastName: 'Kaboré' },
      { id: 'mrn', firstName: 'Paul', lastName: 'Ouédraogo', mrn: 'AWA' },
    ];
    expect(rankPeopleByName(rows, 'awa kabore').map((r) => r.id)[0]).toBe('exact');
    expect(rankPeopleByName(rows, 'Awa').map((r) => r.id)).toEqual([
      'mrn',
      'exact',
      'start',
      'word',
      'contains',
    ]);
    expect(rankPeopleByName(rows, '')).toEqual(rows);
  });

  it('reads the last name too: "Kaboré" finds every Kaboré before a name that contains it', () => {
    const rows = [
      { id: 'x', firstName: 'Abdoul', lastName: 'Sankaboré' },
      { id: 'k', firstName: 'Salif', lastName: 'Kaboré' },
    ];
    expect(rankPeopleByName(rows, 'kabore').map((r) => r.id)).toEqual(['k', 'x']);
  });
});

describe('patientMatchesTerm (QA D1274)', () => {
  const awa = {
    firstName: 'Awa',
    lastName: 'Kaboré',
    email: 'awa.kabore@example.test',
    mrn: 'CHW9395',
    phoneNumberPrimary: '+22670002781',
    dateOfBirth: '2020-03-15',
  };

  it('finds the patient by birth date in every format the desk types it', () => {
    for (const typed of ['2020-03-15', '15/03/2020', '15-03-2020', '15032020']) {
      expect(patientMatchesTerm(awa, typed)).withContext(typed).toBeTrue();
    }
    expect(patientMatchesTerm(awa, '16/03/2020')).toBeFalse();
    expect(birthDateMatches('2020-03-15T00:00:00', '15/03/2020')).toBeTrue();
  });

  it('still finds by name, e-mail (whole or in part), MRN and phone', () => {
    for (const typed of [
      'kabore',
      'Awa Kaboré',
      'awa.kabore',
      'awa.kabore@example.test',
      'chw9395',
      '70 00 27 81',
    ]) {
      expect(patientMatchesTerm(awa, typed)).withContext(typed).toBeTrue();
    }
    expect(patientMatchesTerm(awa, 'Sawadogo')).toBeFalse();
  });
});
