import { safeReturnUrl } from './return-url';

/**
 * This is an open-redirect gate feeding `navigateByUrl` immediately after a
 * successful sign-in, so the rejection cases matter more than the happy path.
 */
describe('safeReturnUrl', () => {
  it('accepts a same-origin path', () => {
    expect(safeReturnUrl('/my-appointments')).toBe('/my-appointments');
  });

  it('keeps query strings, which are the whole point of the deep links', () => {
    expect(safeReturnUrl('/my-appointments?cancel=abc-123')).toBe(
      '/my-appointments?cancel=abc-123',
    );
  });

  it('rejects an absolute URL', () => {
    expect(safeReturnUrl('https://evil.example/login')).toBeNull();
  });

  it('rejects a protocol-relative URL', () => {
    // `//evil.example` navigates off-origin while looking like a path.
    expect(safeReturnUrl('//evil.example/login')).toBeNull();
  });

  it('rejects backslash-disguised protocol-relative URLs', () => {
    // Browsers normalise backslashes to forward slashes, so both of these
    // are `//evil.example` by the time navigation happens.
    expect(safeReturnUrl('/\\evil.example')).toBeNull();
    expect(safeReturnUrl('\\\\evil.example')).toBeNull();
  });

  it('rejects an absolute URL smuggled after a leading slash', () => {
    expect(safeReturnUrl('/https://evil.example')).toBeNull();
  });

  it('rejects a bare host', () => {
    expect(safeReturnUrl('evil.example')).toBeNull();
  });

  it('rejects /login so a successful login cannot bounce back to itself', () => {
    expect(safeReturnUrl('/login')).toBeNull();
    expect(safeReturnUrl('/login/')).toBeNull();
    expect(safeReturnUrl('/login?returnUrl=%2Flogin')).toBeNull();
  });

  it('rejects empty and missing values', () => {
    expect(safeReturnUrl(null)).toBeNull();
    expect(safeReturnUrl(undefined)).toBeNull();
    expect(safeReturnUrl('')).toBeNull();
    expect(safeReturnUrl('   ')).toBeNull();
    expect(safeReturnUrl('/')).toBeNull();
  });
});
