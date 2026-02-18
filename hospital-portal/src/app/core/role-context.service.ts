import { Injectable, signal, computed } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class RoleContextService {
  private readonly _activeHospitalId = signal<string | null>(null);
  private readonly _activeRoles = signal<string[]>([]);

  readonly activeHospitalIdSignal = computed(() => this._activeHospitalId());

  get activeHospitalId(): string | null {
    return this._activeHospitalId();
  }

  set activeHospitalId(id: string | null) {
    this._activeHospitalId.set(id);
  }

  get activeRoles(): string[] {
    return this._activeRoles();
  }

  setRoles(roles: string[]): void {
    this._activeRoles.set(roles);
  }

  hasRole(role: string): boolean {
    return this._activeRoles().includes(role);
  }
}
