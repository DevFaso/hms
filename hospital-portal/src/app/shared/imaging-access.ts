/**
 * QA D1924 (r53): the roles the Imagerie page (`/imaging`) admits, so a page
 * outside it offers the way there only to them. A lab technician or a
 * pharmacist reads the chart review but not Imagerie, and would land on a
 * 403 page. `chart-review.component.spec.ts` checks this list against the
 * route's own `data.roles`.
 */
export const IMAGING_PAGE_ROLES: readonly string[] = [
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_RADIOLOGIST',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_ADMIN',
  'ROLE_SUPER_ADMIN',
];
