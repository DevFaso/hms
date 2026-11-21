import { Injectable, signal } from '@angular/core';

/**
 * Pages reached from the top bar and the profile menu, never from the
 * sidebar: a link to one of them is not a dead link just because the sidebar
 * does not list it.
 */
const ALWAYS_OPEN: readonly string[] = [
  '/dashboard',
  '/chat',
  '/notifications',
  '/announcements',
  '/profile',
  '/settings',
];

/**
 * QA r65 D2761 (class): what the signed-in role can actually open, as the
 * sidebar decides it.
 *
 * <p>The sidebar is the one list that already answers "can this role use
 * this module": each entry is gated on the route's roles AND on the
 * permission the backend grants (`shell.ts`, the result of the role audits).
 * The dashboard had its own, weaker answer (the route's roles alone), so an
 * administrator (`ROLE_ADMIN`) was offered Rendez-vous, Facturation,
 * Personnel and Départements tiles whose routes admit the role while every
 * request behind them is refused: a 403 page, or an empty page inviting them
 * to "create your first department".
 *
 * <p>The shell publishes its entries here; a page that links to another
 * module asks {@link canOpen} first, so a link is offered only where the
 * sidebar offers the module. UX only: route guards and the backend remain
 * the enforcement.
 */
@Injectable({ providedIn: 'root' })
export class NavAccessService {
  private readonly routes = signal<ReadonlySet<string> | null>(null);

  /** The sidebar's routes for the acting role, each time the shell rebuilds them. */
  publish(routes: readonly string[]): void {
    this.routes.set(new Set(routes));
  }

  /** Sign-out, or a shell torn down: the next user is not judged on this one's sidebar. */
  clear(): void {
    this.routes.set(null);
  }

  /**
   * The published set itself (null before any shell): a new object each time
   * the sidebar is rebuilt, so a caller can cache its answers against it.
   */
  snapshot(): ReadonlySet<string> | null {
    return this.routes();
  }

  /**
   * Whether a link to `url` leads somewhere the acting role can use: the
   * page itself, or a page of a module the sidebar lists (`/patients/new`
   * and `/appointments/42` belong to `/patients` and `/appointments`).
   * Before any shell has published (a page rendered on its own) the answer
   * is yes, and the route guards decide as they always did.
   */
  canOpen(url: string): boolean {
    const listed = this.routes();
    if (listed === null) return true;
    const segments = url.split(/[?#]/)[0].split('/').filter(Boolean);
    for (let depth = segments.length; depth > 0; depth--) {
      const path = '/' + segments.slice(0, depth).join('/');
      if (listed.has(path) || ALWAYS_OPEN.includes(path)) return true;
    }
    return false;
  }
}
