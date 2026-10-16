import { Injectable, signal, computed } from '@angular/core';

import { expandRoleEquivalents, roleSatisfies } from './role-equivalence';

const HELD_ROLES_KEY = 'hms.heldRoles';

@Injectable({ providedIn: 'root' })
export class RoleContextService {
  isReceptionist(): boolean {
    // Roles in the JWT/auth context are stored with the `ROLE_` prefix, but
    // older callers historically used the bare form. Accept both shapes so a
    // present-but-prefixed role never silently fails the check (which would
    // hide read-only behaviour and let receptionists see write controls).
    return this.hasRole('ROLE_RECEPTIONIST') || this.hasRole('RECEPTIONIST');
  }
  private readonly _activeHospitalId = signal<string | null>(null);
  private readonly _activeRoles = signal<string[]>([]);
  private readonly _permittedHospitalIds = signal<string[]>([]);

  /**
   * The single role the user chose at login.  When only one role exists
   * this is automatically set.  For multi-role users it is set after the
   * role-picker step in the login component.
   */
  private readonly _activeRole = signal<string | null>(null);

  /**
   * Super-admin cross-tenant scope state
   * (docs/super-admin-cross-tenant-design.md).
   *
   * `_globalView` — when `true` the auth interceptor must omit the
   *   `X-Hospital-Id` header so list endpoints fall through to their
   *   unscoped "all tenants" branch (controlled by
   *   `RoleValidator.requireActiveHospitalId() == null` on the backend).
   *
   * `_selectedHospitalId` — when set (and `_globalView` is `false`), the
   *   interceptor must send `X-Hospital-Id: <selectedHospitalId>` to scope
   *   all reads to that single tenant. This is *separate* from
   *   `_activeHospitalId` (which represents the user's primary/working
   *   hospital), so leaving global view does not clobber it.
   *
   * Both default to inert values; `markSuperAdminGlobalDefaults()` is
   * called once after the JWT is decoded to flip `_globalView` to `true`
   * for super-admins (design call #5: super-admin lands in global view by
   * default — *not* on their primary hospital — so we never silently
   * re-introduce the bug we are fixing).
   */
  private readonly _globalView = signal<boolean>(false);
  private readonly _selectedHospitalId = signal<string | null>(null);

  readonly activeHospitalIdSignal = computed(() => this._activeHospitalId());

  private readonly _providerUser = signal<boolean>(false);

  /**
   * The caller is confined to a provider facility (pharmacy or laboratory),
   * as the session bootstrap states it: what such a user may not reach (the
   * emergency-broadcast socket) is not attempted.
   */
  readonly providerUser = computed(() => this._providerUser());

  setProviderUser(providerUser: boolean): void {
    this._providerUser.set(providerUser);
  }

  private readonly _sessionResolved = signal<boolean>(false);

  /**
   * The session bootstrap has been applied on this page (or, when the server
   * could not be asked, the stored profile stands in): until then
   * {@link providerUser} is only its initial false, not an answer.
   */
  readonly sessionResolved = computed(() => this._sessionResolved());

  markSessionResolved(): void {
    this._sessionResolved.set(true);
  }

  /** Sign-out: the next user starts unresolved and unconfined, never with the last user's flags. */
  clearSession(): void {
    this._providerUser.set(false);
    this._sessionResolved.set(false);
    this.setHeldRoles([]);
    this._refusedRoles.set(null);
  }

  /**
   * Every role the account holds, as the login role picker listed them. The
   * JWT carries only the role picked there, so without this a refused page
   * cannot tell the user that another of their roles would open it. Kept in
   * sessionStorage so a reload of the 403 page still knows it; a convenience
   * only, never an authorization input.
   */
  private readonly _heldRoles = signal<string[]>(RoleContextService.readHeldRoles());

  /** The roles the last refused route asked for; null when the refusal came from the server. */
  private readonly _refusedRoles = signal<string[] | null>(null);

  get heldRoles(): string[] {
    return this._heldRoles();
  }

  setHeldRoles(roles: string[]): void {
    this._heldRoles.set(roles);
    try {
      if (roles.length) {
        sessionStorage.setItem(HELD_ROLES_KEY, JSON.stringify(roles));
      } else {
        sessionStorage.removeItem(HELD_ROLES_KEY);
      }
    } catch {
      // Storage blocked: the hint simply has less to go on after a reload.
    }
  }

  /** Records what a refused route required, for the 403 page's hint. */
  noteRefusal(requiredRoles: string[] | null): void {
    this._refusedRoles.set(requiredRoles);
  }

  /**
   * A role the account holds, other than the active one, that would have
   * opened the refused page: the first held role satisfying the route's
   * roles, or — for a server refusal, whose required roles the portal cannot
   * know — ROLE_SUPER_ADMIN when held, since it reaches everything else.
   */
  roleThatWouldAllow(): string | null {
    const active = this._activeRole();
    if (!active) return null;
    const others = this._heldRoles().filter((r) => r !== active);
    const required = this._refusedRoles();
    if (required?.length) {
      return others.find((r) => roleSatisfies(required, r)) ?? null;
    }
    return others.includes('ROLE_SUPER_ADMIN') ? 'ROLE_SUPER_ADMIN' : null;
  }

  private static readHeldRoles(): string[] {
    try {
      const raw = sessionStorage.getItem(HELD_ROLES_KEY);
      const parsed: unknown = raw ? JSON.parse(raw) : [];
      return Array.isArray(parsed) ? parsed.filter((r): r is string => typeof r === 'string') : [];
    } catch {
      return [];
    }
  }

  /** True when the current user holds ROLE_SUPER_ADMIN. */
  readonly isSuperAdmin = computed(() => this._activeRoles().includes('ROLE_SUPER_ADMIN'));

  /** Cross-tenant "all hospitals" mode (super-admin only). */
  readonly globalView = computed(() => this._globalView());

  /** The hospital UUID currently scoped via the chip (null in global view). */
  readonly selectedHospitalId = computed(() => this._selectedHospitalId());

  /**
   * What the auth interceptor should send as `X-Hospital-Id` for the
   * current request (or `null` to skip the header entirely).
   *
   * Rules:
   *   - super-admin in global view  → `null` (omit header → backend returns all tenants)
   *   - super-admin scoped to a hospital → that hospital's UUID
   *   - non-super-admin → their `_activeHospitalId` (existing behaviour)
   */
  /**
   * True when a hospital-pinned request can be made right now: staff are
   * always pinned by their assignment, a super-admin only once the scope chip
   * has picked a hospital. Pages whose data belongs to one facility gate their
   * loads and their write buttons on this and show the scope hint otherwise.
   */
  readonly hasHospitalScope = computed(() => this.effectiveHospitalIdForRequest() != null);

  readonly effectiveHospitalIdForRequest = computed(() => {
    if (this._activeRoles().includes('ROLE_SUPER_ADMIN')) {
      if (this._globalView()) {
        return null;
      }
      return this._selectedHospitalId() ?? this._activeHospitalId();
    }
    return this._activeHospitalId();
  });

  get activeHospitalId(): string | null {
    return this._activeHospitalId();
  }

  set activeHospitalId(id: string | null) {
    this._activeHospitalId.set(id);
  }

  get activeRoles(): string[] {
    return this._activeRoles();
  }

  /** The single role the user selected at login (or the only role they hold). */
  get activeRole(): string | null {
    return this._activeRole();
  }

  set activeRole(role: string | null) {
    this._activeRole.set(role);
  }

  /** All hospital IDs this user is permitted to access, as the session states them (E9 #55b). */
  get permittedHospitalIds(): string[] {
    return this._permittedHospitalIds();
  }

  setRoles(roles: string[]): void {
    this._activeRoles.set(roles);
    // Auto-set activeRole when exactly one role
    if (roles.length === 1) {
      this._activeRole.set(roles[0]);
    }
  }

  setPermittedHospitalIds(ids: string[]): void {
    this._permittedHospitalIds.set(ids);
  }

  hasRole(role: string): boolean {
    return this._activeRoles().includes(role);
  }

  /**
   * Active-role-aware check for gating page actions. When the user picked an
   * active role at login only that role counts (a multi-role user scoped to
   * NURSE must not see doctor-only actions); otherwise all JWT roles apply.
   *
   * Applies the same doctor equivalence as `RoleGuard` and the shell nav
   * (`role-equivalence.ts`, role audit C2): a physician or surgeon satisfies a
   * list naming ROLE_DOCTOR. It used to compare raw strings, so every
   * in-component gate was narrower than the route hosting it and hid controls
   * the backend would serve. The expansion widens the HELD roles, never the
   * list asked about: a list with no ROLE_DOCTOR in it (the pharmacist-verify
   * gate, say) admits exactly whom it admitted before.
   */
  hasAnyActiveRole(roles: string[]): boolean {
    const active = this._activeRole();
    if (active) return roleSatisfies(roles, active);
    return expandRoleEquivalents(this._activeRoles()).some((r) => roles.includes(r));
  }

  /**
   * `hasAnyActiveRole` WITHOUT the doctor equivalence, for the few gates whose
   * backend admits physicians and surgeons at the annotation and then refuses
   * them one layer down (`RoleValidator.isDoctor` matches the stored
   * assignment code DOCTOR only). Expanding there would offer a control that
   * can only fail. Each caller says which service check it mirrors.
   */
  hasAnyActiveRoleExactly(roles: string[]): boolean {
    const active = this._activeRole();
    if (active) return roles.includes(active);
    return roles.some((r) => this._activeRoles().includes(r));
  }

  /**
   * Switch the super-admin's cross-tenant scope to "all hospitals".
   *
   * Clears `_selectedHospitalId` so a subsequent navigation that reads it
   * (e.g. URL query-param sync) doesn't re-pin to the previous tenant.
   */
  enableGlobalView(): void {
    this._globalView.set(true);
    this._selectedHospitalId.set(null);
  }

  /**
   * Pin the super-admin to a single hospital. Idempotent — passing the
   * same UUID twice is a no-op.
   */
  scopeToHospital(hospitalId: string): void {
    if (!hospitalId) {
      return;
    }
    this._globalView.set(false);
    this._selectedHospitalId.set(hospitalId);
  }

  /**
   * Called once after the JWT is decoded and `setRoles(...)` is invoked.
   * Implements design call #5: super-admin lands on each list page in
   * global view by default. For non-super-admins this is a no-op so we
   * preserve their existing single-tenant behaviour.
   */
  markSuperAdminGlobalDefaults(): void {
    if (this._activeRoles().includes('ROLE_SUPER_ADMIN')) {
      this._globalView.set(true);
      this._selectedHospitalId.set(null);
    }
  }
}
