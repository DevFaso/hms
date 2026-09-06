import { RoleContextService } from '../core/role-context.service';

/** Mutable scope a spec can flip between tests. */
export interface RoleContextStubState {
  superAdmin: boolean;
  /** The pinned hospital; null = super-admin global view (or staff with none). */
  hospitalId: string | null;
  roles: string[];
}

/**
 * One `RoleContextService` stand-in for page specs, so the members the scope
 * chip and the scope hint read (`isSuperAdmin`, `globalView`,
 * `selectedHospitalId`, `effectiveHospitalIdForRequest`, `hasHospitalScope`,
 * `enableGlobalView`, `scopeToHospital`) exist once instead of in every spec.
 * Read `state` live so a test can switch to global view before `detectChanges`.
 */
export function roleContextStub(state: RoleContextStubState): RoleContextService {
  return {
    isSuperAdmin: () => state.superAdmin,
    globalView: () => state.superAdmin && state.hospitalId == null,
    selectedHospitalId: () => (state.superAdmin ? state.hospitalId : null),
    effectiveHospitalIdForRequest: () => state.hospitalId,
    hasHospitalScope: () => state.hospitalId != null,
    get activeHospitalId() {
      return state.hospitalId;
    },
    hasAnyActiveRole: (roles: string[]) => roles.some((r) => state.roles.includes(r)),
    enableGlobalView: () => {
      state.hospitalId = null;
    },
    scopeToHospital: (id: string) => {
      state.hospitalId = id;
    },
  } as unknown as RoleContextService;
}
