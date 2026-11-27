import { ParamMap } from '@angular/router';

/** What an account-link page needs of its route. */
export interface AccountLinkRoute {
  fragment?: string | null;
  queryParamMap: ParamMap;
}

/**
 * The single-use token of an e-mailed or texted account link (activation, password reset).
 *
 * The link carries it in the URL fragment (`…/verify#token=…`), which the browser never sends to
 * the host serving the portal. The page reads it here, clears the address bar and exchanges it by
 * POST. A link sent before this rule carried it in the query string and still works until it expires.
 */
export function linkToken(route: AccountLinkRoute): string {
  const inFragment = new URLSearchParams(route.fragment ?? '').get('token');
  return inFragment || route.queryParamMap.get('token') || '';
}
