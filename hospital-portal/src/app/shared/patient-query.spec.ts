import { birthDateQuery, emailQuery, isCompactDate } from './patient-query';

describe('patient-query (QA D1274)', () => {
  it('reads a birth date in every format the desk writes it', () => {
    for (const typed of [
      '2020-03-15',
      '2020/03/15',
      '15/03/2020',
      '15-03-2020',
      '15.03.2020',
      '15032020',
      '5/3/2020',
    ]) {
      expect(birthDateQuery(typed))
        .withContext(typed)
        .toBe(typed === '5/3/2020' ? '2020-03-05' : '2020-03-15');
    }
  });

  it('is not fooled by a phone number, an MRN, a name or an impossible day', () => {
    for (const typed of [
      '70002781',
      '+22670002781',
      'CHW9395',
      'Awa',
      '31/02/2020',
      '2020-13-01',
      '00000000',
      '',
    ]) {
      expect(birthDateQuery(typed)).withContext(typed).toBeNull();
    }
    expect(birthDateQuery('29/02/2024')).toBe('2024-02-29');
    expect(birthDateQuery('29/02/2023')).toBeNull();
  });

  it('knows that eight digits of a date are also a phone number', () => {
    expect(isCompactDate('15032020')).toBeTrue();
    expect(isCompactDate('15/03/2020')).toBeFalse();
    expect(isCompactDate('70002781')).toBeFalse();
  });

  it('reads an e-mail, whole or in part, and nothing else', () => {
    expect(emailQuery('Awa.Kabore@Example.test')).toBe('awa.kabore@example.test');
    expect(emailQuery('awa.kabore')).toBe('awa.kabore');
    expect(emailQuery('kabore@')).toBe('kabore@');
    expect(emailQuery('awa_kabore')).toBe('awa_kabore');
    for (const typed of ['Awa Kaboré', 'Jean-Pierre', 'Awa', '70002781', '15.03.2020', 'a.']) {
      expect(emailQuery(typed)).withContext(typed).toBeNull();
    }
  });
});
