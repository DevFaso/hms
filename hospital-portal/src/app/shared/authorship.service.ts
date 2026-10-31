import { Injectable, inject } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';

/** A clinical order or document as the server describes its author. */
export interface AuthoredRow {
  /**
   * The user the server's author check compares the caller with (the ordering
   * clinician, the requester, the referring midwife…). Absent on a server or
   * row that does not say: the action is then offered as before, and the
   * server's refusal (shown in a toast, D995) stays the backstop.
   */
  authorUserId?: string | null;
}

/**
 * QA D1081 (D995 class): one rule for every author-only action on an order or
 * document list — cancel, withdraw, void, edit, sign. The lists offered
 * "Annuler" to every doctor and the server refused all but the author after
 * the confirm. A super-admin is always offered it (the server lets a
 * super-admin act on any record).
 */
@Injectable({ providedIn: 'root' })
export class AuthorshipService {
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);

  /**
   * @param row the record
   * @param fallback the decision when the row names no author user (a list
   *   that already compared another author key keeps that comparison here)
   */
  isAuthor(row: AuthoredRow | null | undefined, fallback = true): boolean {
    if (this.roleContext.isSuperAdmin()) return true;
    const author = row?.authorUserId;
    if (!author) return fallback;
    const me = this.auth.getUserProfile()?.id ?? this.auth.getUserId();
    return !!me && author === me;
  }
}
