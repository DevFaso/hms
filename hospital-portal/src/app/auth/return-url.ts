/**
 * Validation for the `returnUrl` the AuthGuard attaches when it bounces an
 * unauthenticated deep link to `/login`.
 *
 * <p>This is an open-redirect gate, not a formatting helper. The value
 * arrives in a query string the user (or whoever sent them the link)
 * controls, and it is fed to `Router.navigateByUrl` immediately after a
 * successful sign-in — the single most valuable moment to redirect somebody
 * to a credential-harvesting copy of this app. So it is an allow-list: a
 * same-origin absolute path, or nothing.
 *
 * <p>Lives in its own file because two callers need it — the login component
 * and `LoginRedirectGuard` — and a security check implemented twice is a
 * security check implemented wrong once.
 */

/**
 * Returns `raw` when it is a safe same-origin path, otherwise `null`.
 *
 * Rejected, with the reason each one matters:
 * - anything not starting with `/` — a bare `evil.com` is resolved as a
 *   relative path by some routers and as a host by others
 * - `//evil.com` — protocol-relative, navigates off-origin
 * - `/\evil.com` and `\\evil.com` — browsers normalise backslashes to
 *   forward slashes, so these are protocol-relative in disguise
 * - anything containing `://` — an absolute URL smuggled past the above
 * - `/login` and its variants — bouncing back to the login page after a
 *   successful login is a loop, not a destination
 */
export function safeReturnUrl(raw: string | null | undefined): string | null {
  if (!raw) {
    return null;
  }
  const value = raw.trim();
  if (!value.startsWith('/')) {
    return null;
  }
  // Normalise backslashes before the protocol-relative check: the browser
  // will, so checking the raw form alone would miss `/\evil.com`.
  const normalized = value.replace(/\\/g, '/');
  if (normalized.startsWith('//')) {
    return null;
  }
  if (normalized.includes('://')) {
    return null;
  }
  const path = normalized.split(/[?#]/)[0].replace(/\/+$/, '');
  if (path === '/login' || path === '') {
    return null;
  }
  return value;
}
