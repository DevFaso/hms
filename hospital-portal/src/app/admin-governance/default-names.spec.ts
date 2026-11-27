import { governanceNameKey } from './default-names';

describe('governanceNameKey (QA D4318)', () => {
  it('reads a platform-written name through its key', () => {
    expect(governanceNameKey('Default Password Policy')).toBe(
      'ADMIN_GOV.DEFAULT_NAME.PASSWORD_POLICY',
    );
    expect(governanceNameKey(' Session Timeout ')).toBe('ADMIN_GOV.DEFAULT_NAME.SESSION_TIMEOUT');
  });

  it('leaves a name a person typed as it is', () => {
    expect(governanceNameKey('Politique maison')).toBeNull();
    expect(governanceNameKey(null)).toBeNull();
  });
});
