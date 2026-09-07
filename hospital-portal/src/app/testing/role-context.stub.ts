import { RoleContextService } from '../core/role-context.service';

/** Mutable scope a spec can flip between tests. */
export interface RoleContextStubState {
  superAdmin: boolean;
  /**
   * The hospital the account is pinned to. For staff it is their assignment;
   * for a super-admin it seeds both the primary hospital and the chip's pick.
   * null = a super-admin in global view (or staff with no hospital yet).
   */
  hospitalId: string | null;
  roles: string[];
}

/**
 * One `RoleContextService` stand-in for page specs, mirroring the real
 * service's scope rules: the chip's pick (`scopeToHospital`) and global view
 * (`enableGlobalView`) only move a super-admin's selected hospital, never the
 * primary one, exactly as `effectiveHospitalIdForRequest` treats them. Read
 * `state` live so a test can switch scope before `detectChanges`.
 */
export function roleContextStub(state: RoleContextStubState): RoleContextService {
  // Until the chip (or the URL sync) picks, everything derives from `state`
  // live — so a test may flip `state` after building the stub. A pick sets an
  // override that, like the real service, moves only the selected hospital.
  let override: { globalView: boolean; selected: string | null } | null = null;
  const globalView = (): boolean =>
    override ? override.globalView : state.superAdmin && state.hospitalId == null;
  const selected = (): string | null => (override ? override.selected : state.hospitalId);
  const effective = (): string | null => {
    if (state.superAdmin) {
      return globalView() ? null : (selected() ?? state.hospitalId);
    }
    return state.hospitalId;
  };
  return {
    isSuperAdmin: () => state.superAdmin,
    globalView: () => state.superAdmin && globalView(),
    selectedHospitalId: () => (state.superAdmin ? selected() : null),
    effectiveHospitalIdForRequest: effective,
    hasHospitalScope: () => effective() != null,
    get activeHospitalId() {
      return state.hospitalId;
    },
    hasAnyActiveRole: (roles: string[]) => roles.some((r) => state.roles.includes(r)),
    enableGlobalView: () => {
      override = { globalView: true, selected: null };
    },
    scopeToHospital: (id: string) => {
      override = { globalView: false, selected: id };
    },
  } as unknown as RoleContextService;
}
