import { Injectable, signal, computed } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class RoleContextService {
  isReceptionist(): boolean {
    return this.hasRole('ROLE_RECEPTIONIST');
  }
  private readonly _activeHospitalId = signal<string | null>(null);
  private readonly _activeRoles = signal<string[]>([]);
  private readonly _permittedHospitalIds = signal<string[]>([]);

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

  /** All hospital IDs this user is permitted to access, decoded from the JWT. */
  get permittedHospitalIds(): string[] {
    return this._permittedHospitalIds();
  }

  setRoles(roles: string[]): void {
    this._activeRoles.set(roles);
  }

  setPermittedHospitalIds(ids: string[]): void {
    this._permittedHospitalIds.set(ids);
  }

  hasRole(role: string): boolean {
    return this._activeRoles().includes(role);
  }
}
