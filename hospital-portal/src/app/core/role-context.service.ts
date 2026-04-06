import { Injectable, signal, computed } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class RoleContextService {
  isReceptionist(): boolean {
    return this.hasRole('RECEPTIONIST');
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

  readonly activeHospitalIdSignal = computed(() => this._activeHospitalId());

  /** True when the current user holds ROLE_SUPER_ADMIN. */
  readonly isSuperAdmin = computed(() => this._activeRoles().includes('ROLE_SUPER_ADMIN'));

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
}
