import { computed, signal } from '@angular/core';
import { RoleContextService } from '../core/role-context.service';

/**
 * The scope a stub starts from. Read-only on purpose: the stub copies it into
 * signals, so mutating the object afterwards would change nothing — a spec
 * that needs another scope calls `stub.set({...})`, which notifies every
 * `computed()` and template reading it, exactly as the real service does.
 */
export interface RoleContextStubState {
  readonly superAdmin: boolean;
  /**
   * The hospital the account is pinned to. For staff it is their assignment;
   * for a super-admin it seeds both the primary hospital and the chip's pick.
   * null = a super-admin in global view (or staff with no hospital yet).
   */
  readonly hospitalId: string | null;
  readonly roles: readonly string[];
  /**
   * The role picked at login. Omitted, it follows the real service's
   * `setRoles`: pinned automatically when the account holds exactly one role,
   * none otherwise. Set it with several `roles` to model a multi-role user
   * pinned to one of them — the case `hasAnyActiveRole` exists for.
   */
  readonly activeRole?: string | null;
}

/** The stub's extra surface: move the scope the way a login or a chip would. */
export interface RoleContextStubControls {
  /** Replace part of the scope. A new hospital also clears any chip pick. */
  set(patch: Partial<RoleContextStubState>): void;
}

export type RoleContextStub = RoleContextService & RoleContextStubControls;

/**
 * One `RoleContextService` stand-in for page specs, backed by real signals so
 * a `computed()` over it recomputes and `detectChanges()` re-renders after
 * `set(...)`. It mirrors the real service's scope rules: the chip's pick
 * (`scopeToHospital`) and global view (`enableGlobalView`) only move a
 * super-admin's selected hospital, never the primary one, exactly as
 * `effectiveHospitalIdForRequest` treats them.
 */
export function roleContextStub(initial: RoleContextStubState): RoleContextStub {
  const superAdmin = signal(initial.superAdmin);
  const hospitalId = signal<string | null>(initial.hospitalId);
  const roles = signal<string[]>([...initial.roles]);
  const pickedRole = signal<string | null | undefined>(initial.activeRole);
  // A chip (or URL sync) pick overrides the global-view default derived from
  // `hospitalId`, and like the real service moves only the selected hospital.
  const override = signal<{ globalView: boolean; selected: string | null } | null>(null);

  const activeRole = computed(() => {
    const picked = pickedRole();
    if (picked !== undefined) return picked;
    const all = roles();
    return all.length === 1 ? all[0] : null;
  });
  const rawGlobalView = computed(() => {
    const o = override();
    return o ? o.globalView : superAdmin() && hospitalId() == null;
  });
  const selected = computed(() => {
    const o = override();
    return o ? o.selected : hospitalId();
  });
  const effective = computed(() => {
    if (superAdmin()) {
      return rawGlobalView() ? null : (selected() ?? hospitalId());
    }
    return hospitalId();
  });
  const hasRole = (role: string): boolean => roles().includes(role);

  const stub = {
    isSuperAdmin: computed(() => superAdmin()),
    globalView: computed(() => superAdmin() && rawGlobalView()),
    selectedHospitalId: computed(() => (superAdmin() ? selected() : null)),
    effectiveHospitalIdForRequest: effective,
    hasHospitalScope: computed(() => effective() != null),
    activeHospitalIdSignal: computed(() => hospitalId()),
    get activeHospitalId(): string | null {
      return hospitalId();
    },
    get activeRoles(): string[] {
      return roles();
    },
    get activeRole(): string | null {
      return activeRole();
    },
    get permittedHospitalIds(): string[] {
      const id = hospitalId();
      return id ? [id] : [];
    },
    hasRole,
    isReceptionist: () => hasRole('ROLE_RECEPTIONIST') || hasRole('RECEPTIONIST'),
    // Same rule as the real service: a picked role is the only one that counts.
    hasAnyActiveRole: (wanted: string[]) => {
      const active = activeRole();
      if (active) return wanted.includes(active);
      return wanted.some((r) => roles().includes(r));
    },
    enableGlobalView: () => override.set({ globalView: true, selected: null }),
    scopeToHospital: (id: string) => {
      if (id) override.set({ globalView: false, selected: id });
    },
    set: (patch: Partial<RoleContextStubState>) => {
      if (patch.superAdmin !== undefined) superAdmin.set(patch.superAdmin);
      if (patch.hospitalId !== undefined) {
        hospitalId.set(patch.hospitalId);
        override.set(null);
      }
      if (patch.roles !== undefined) {
        roles.set([...patch.roles]);
        if (patch.activeRole === undefined) pickedRole.set(undefined);
      }
      if (patch.activeRole !== undefined) pickedRole.set(patch.activeRole);
    },
  };
  return stub as unknown as RoleContextStub;
}
