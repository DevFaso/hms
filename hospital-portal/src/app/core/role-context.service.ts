import { Injectable, signal, computed } from '@angular/core';

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

  /** All hospital IDs this user is permitted to access, decoded from the JWT. */
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
   */
  hasAnyActiveRole(roles: string[]): boolean {
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
