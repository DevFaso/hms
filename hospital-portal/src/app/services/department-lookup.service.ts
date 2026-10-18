import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/** A department as it appears in a picker: just enough to choose one. */
export interface DepartmentOption {
  id: string;
  name: string;
}

/**
 * Active departments for a hospital, for dropdowns.
 *
 * <p>This exists because two features were deriving their department list
 * from the STAFF list instead — collecting distinct `departmentId` values off
 * staff members. That makes a department visible only if somebody is assigned
 * to it, so a real, active department with no staff yet is silently missing
 * from the picker, and the user cannot select what they can plainly see in
 * the admin screens.
 *
 * <p>`/departments/active-minimal/{hospitalId}` is purpose-built for this and
 * already permits DOCTOR, NURSE, MIDWIFE and RECEPTIONIST, so no role gap
 * forced the workaround. Kept in one place so encounters, admissions and
 * referrals cannot drift apart again.
 */
/**
 * The department endpoints answer with a plain array (`List<…>` on the
 * server), but older callers expected a `{ data }` envelope or a `{ content }`
 * page and read `undefined` from the array, so every department picker was
 * empty (QA D75). Accept all three shapes so a future wrapper cannot empty
 * the pickers again.
 */
export function unwrapDepartmentList<T>(
  res: T[] | { data?: T[]; content?: T[] } | null | undefined,
): T[] {
  if (Array.isArray(res)) return res;
  return res?.data ?? res?.content ?? [];
}

@Injectable({ providedIn: 'root' })
export class DepartmentLookupService {
  private readonly http = inject(HttpClient);

  /**
   * Active departments at `hospitalId`, or an empty list if no hospital is
   * given yet.
   *
   * <p>Errors are NOT swallowed into an empty list: an empty picker and a
   * failed request look identical to the user but mean opposite things — one
   * says "this hospital has no departments", the other says "we could not
   * ask". Callers render the difference.
   */
  getActiveDepartments(hospitalId: string): Observable<DepartmentOption[]> {
    return this.http
      .get<DepartmentOption[]>(`/departments/active-minimal/${hospitalId}`)
      .pipe(map((res) => unwrapDepartmentList(res)));
  }
}
