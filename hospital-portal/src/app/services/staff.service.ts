import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map, Observable } from 'rxjs';

export interface StaffResponse {
  id: string;
  userId: string;
  username: string;
  name: string;
  email: string;
  phoneNumber?: string;
  hospitalId: string;
  hospitalName?: string;
  hospitalEmail?: string;
  departmentId?: string;
  departmentName?: string;
  departmentEmail?: string;
  departmentPhoneNumber?: string;
  /** The staff member's role assignment at this hospital (QA D872). */
  assignmentId?: string;
  roleCode?: string;
  roleName?: string;
  jobTitle?: string;
  employmentType?: string;
  specialization?: string;
  licenseNumber?: string;
  /** Absent for a qualification that does not expire — a diploma. */
  licenseExpiryDate?: string;
  startDate?: string;
  endDate?: string;
  active: boolean;
  headOfDepartment?: boolean;
  createdAt: string;
  updatedAt?: string;
}

export interface StaffUpsertRequest {
  userEmail: string;
  hospitalName: string;
  departmentName?: string;
  specialization?: string;
  licenseNumber?: string;
  jobTitle?: string;
  employmentType?: string;
  startDate?: string;
  roleName?: string;
}

export interface LabStaffRoleUpdateRequest {
  roleCode: string;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * Role codes whose holders may write a prescription and see patients as the
 * accountable provider. Mirrors hospital-core's RoleValidator.canCreatePrescription
 * (doctor, physician, surgeon, nurse, midwife) — the server refuses any other
 * prescriber, so a picker must not offer an admin or a receptionist.
 */
export const CLINICAL_PROVIDER_ROLE_CODES: readonly string[] = [
  'DOCTOR',
  'PHYSICIAN',
  'SURGEON',
  'NURSE',
  'MIDWIFE',
];

/**
 * Role codes the server accepts as admitting, attending or discharging
 * provider: physicians only (AdmissionServiceImpl.requirePhysician, QA D84 and
 * D176). A nurse or a midwife is refused, so a picker must not offer one.
 */
export const PHYSICIAN_ROLE_CODES: readonly string[] = ['DOCTOR', 'PHYSICIAN', 'SURGEON'];

/** True when the staff member's assignment role is a physician's. */
export function isPhysician(staff: Pick<StaffResponse, 'roleCode'>): boolean {
  const code = (staff.roleCode ?? '').toUpperCase().replace(/^ROLE_/, '');
  return PHYSICIAN_ROLE_CODES.includes(code);
}

/** True when the staff member's assignment role is a clinical provider role. */
export function isClinicalProvider(staff: Pick<StaffResponse, 'roleCode'>): boolean {
  const code = (staff.roleCode ?? '').toUpperCase().replace(/^ROLE_/, '');
  return CLINICAL_PROVIDER_ROLE_CODES.includes(code);
}

@Injectable({ providedIn: 'root' })
export class StaffService {
  private readonly http = inject(HttpClient);

  list(hospitalId?: string): Observable<StaffResponse[]> {
    if (hospitalId) {
      // Use the hospital-scoped active endpoint which filters correctly
      return this.http
        .get<{ content: StaffResponse[] }>(`/staff/hospital/${hospitalId}/active`, {
          params: new HttpParams().set('size', '200'),
        })
        .pipe(map((page) => page.content ?? []));
    }
    return this.http.get<StaffResponse[]>('/staff');
  }

  getById(id: string): Observable<StaffResponse> {
    return this.http.get<StaffResponse>(`/staff/${id}/active`);
  }

  create(req: StaffUpsertRequest): Observable<StaffResponse> {
    return this.http.post<StaffResponse>('/staff', req);
  }

  update(id: string, req: Partial<StaffUpsertRequest>): Observable<StaffResponse> {
    return this.http.put<StaffResponse>(`/staff/${id}`, req);
  }

  deactivate(id: string): Observable<void> {
    return this.http.delete<void>(`/staff/${id}`);
  }

  // ── MVP 3: Lab Staff Management ─────────────────────────────

  getLabStaff(hospitalId: string, page = 0, size = 20): Observable<PagedResponse<StaffResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PagedResponse<StaffResponse>>(`/staff/hospital/${hospitalId}/lab`, {
      params,
    });
  }

  updateLabStaffRole(staffId: string, roleCode: string): Observable<StaffResponse> {
    const body: LabStaffRoleUpdateRequest = { roleCode };
    return this.http.put<StaffResponse>(`/staff/${staffId}/lab-role`, body);
  }
}
