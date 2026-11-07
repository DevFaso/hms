import { emailAddresses, invalidEmailAddresses, isEmailAddress } from './email-address';

describe('email-address (QA D1179)', () => {
  it('accepts the addresses people type', () => {
    for (const ok of ['a@x.test', 'awa.ouedraogo@clinique.bf', 'first+tag@sub.domain.org']) {
      expect(isEmailAddress(ok)).withContext(ok).toBeTrue();
    }
  });

  it('refuses the mistakes QA typed and their siblings', () => {
    for (const bad of [
      'pas-un-email',
      '',
      '   ',
      '@x.test',
      'a@',
      'a@x',
      'a@.test',
      'a@x.',
      'a@x..test',
      'a@@x.test',
      'a b@x.test',
    ]) {
      expect(isEmailAddress(bad)).withContext(JSON.stringify(bad)).toBeFalse();
    }
  });

  it('splits a To field on commas, semicolons and spaces, and names the bad ones in order', () => {
    const list = emailAddresses(' a@x.test, nope ;b@y.test  bad@ ');
    expect(list).toEqual(['a@x.test', 'nope', 'b@y.test', 'bad@']);
    expect(invalidEmailAddresses(list)).toEqual(['nope', 'bad@']);
    expect(emailAddresses(null)).toEqual([]);
  });
});
