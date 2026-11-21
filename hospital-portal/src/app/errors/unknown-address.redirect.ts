import { inject } from '@angular/core';
import { RedirectFunction } from '@angular/router';

import { AuthService } from '../auth/auth.service';

/**
 * QA D2802 (r65): where an address no route matches leads.
 *
 * Signed in: the « page introuvable » page inside the shell, so a mistyped or
 * stale link says so instead of landing silently on the dashboard.
 *
 * Signed out: the login page, with no return address. Sending a signed-out
 * visitor through `/error/404` would make the shell's AuthGuard keep it as the
 * `returnUrl`, and the first page after signing in would be « page
 * introuvable » for someone who only opened a stale bookmark.
 */
export const unknownAddressRedirect: RedirectFunction = () =>
  inject(AuthService).isAuthenticated() ? 'error/404' : 'login';
